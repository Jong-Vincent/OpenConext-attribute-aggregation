package aa.web;

import aa.authz.AuthzResourceServerTokenServices;
import aa.authz.AuthzSchacHomeAwareUserAuthenticationConverter;
import aa.oauth.CachedRemoteTokenServices;
import aa.oauth.CompositeDecisionResourceServerTokenServices;
import aa.oauth.DecisionResourceServerTokenServices;
import aa.oidc.OidcRemoteTokenServices;
import aa.shibboleth.ShibbolethPreAuthenticatedProcessingFilter;
import aa.shibboleth.ShibbolethUserDetailService;
import aa.shibboleth.mock.MockShibbolethFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableResourceServer;
import org.springframework.security.oauth2.config.annotation.web.configuration.ResourceServerConfigurer;
import org.springframework.security.oauth2.config.annotation.web.configuration.ResourceServerConfigurerAdapter;
import org.springframework.security.oauth2.config.annotation.web.configurers.ResourceServerSecurityConfigurer;
import org.springframework.security.oauth2.provider.authentication.BearerTokenExtractor;
import org.springframework.security.oauth2.provider.authentication.TokenExtractor;
import org.springframework.security.oauth2.provider.token.DefaultAccessTokenConverter;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;

/**
 * Protect endpoints for the internal API with Shibboleth AbstractPreAuthenticatedProcessingFilter.
 * <p>
 * Protect the internal endpoint for EB with basic authentication.
 * <p>
 * Protect all other endpoints - except the public ones - with OAuth2 with support for both Authz and OIDC.
 * <p>
 * Do not protect public endpoints like /health, /info and /ServiceProviderConfig
 * <p>
 * Protect the /Me endpoint with an OAuth2 access_token associated with an User authentication
 * <p>
 * Protect the /Schema endpoint with an OAuth2 client credentials access_token
 */
@Configuration
@EnableWebSecurity
@EnableResourceServer
public class WebSecurityConfigurer {

  @Autowired
  public void configureGlobal(AuthenticationManagerBuilder auth) throws Exception {
    //because Autowired this will end up in the global ProviderManager
    PreAuthenticatedAuthenticationProvider authenticationProvider = new PreAuthenticatedAuthenticationProvider();
    authenticationProvider.setPreAuthenticatedUserDetailsService(new ShibbolethUserDetailService());
    auth.authenticationProvider(authenticationProvider);
  }

  @Order(1)
  @Configuration
  public static class InternalSecurityConfigurationAdapter extends WebSecurityConfigurerAdapter {

    @Autowired
    private Environment environment;

    @Override
    public void configure(WebSecurity web) throws Exception {
      web.ignoring().antMatchers("/health", "/v2/ServiceProviderConfig");
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
      http
          .antMatcher("/internal/**")
          .sessionManagement()
          .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
          .and()
          .csrf()
          .requireCsrfProtectionMatcher(new CsrfProtectionMatcher())
          .and()
          .addFilterAfter(new CsrfTokenResponseHeaderBindingFilter(), CsrfFilter.class)
          .addFilterBefore(new SessionAliveFilter(), CsrfFilter.class)
          .addFilterBefore(
              new ShibbolethPreAuthenticatedProcessingFilter(authenticationManagerBean()),
              AbstractPreAuthenticatedProcessingFilter.class
          )
          .authorizeRequests()
          .antMatchers("/internal/**").hasRole("ADMIN");

      if (environment.acceptsProfiles("no-csrf")) {
        http.csrf().disable();
      }
      if (environment.acceptsProfiles("dev", "no-csrf")) {
        //we can't use @Profile, because we need to add it before the real filter
        http.addFilterBefore(new MockShibbolethFilter(), ShibbolethPreAuthenticatedProcessingFilter.class);
      }
    }
  }

  @Configuration
  @Order
  public static class ScimSecurityConfigurationAdapter extends WebSecurityConfigurerAdapter implements ResourceServerConfigurer {

    @Value("${attribute.aggregation.user.name}")
    private String attributeAggregationUserName;

    @Value("${attribute.aggregation.user.password}")
    private String attributeAggregationPassword;

    private boolean configured = false;

    @Value("${authz.checkToken.endpoint.url}")
    private String authzCheckTokenEndpointUrl;

    @Value("${authz.checkToken.clientId}")
    private String authzCheckTokenClientId;

    @Value("${authz.checkToken.secret}")
    private String authzCheckTokenSecret;

    @Value("${oidc.checkToken.endpoint.url}")
    private String oidcCheckTokenEndpointUrl;

    @Value("${oidc.checkToken.clientId}")
    private String oidcCheckTokenClientId;

    @Value("${oidc.checkToken.secret}")
    private String oidcCheckTokenSecret;

    @Value("${checkToken.cache}")
    private boolean checkTokenCache;

    @Value("${checkToken.cache.duration.milliSeconds}")
    private int checkTokenCacheDurationMilliseconds;

    @Override
    public void configure(HttpSecurity http) throws Exception {
      //because we are both WebSecurityConfigurer and ResourceServerConfigurer
      if (configured) {
        return;
      }
      configured = true;
      http
          .antMatcher("/**")
          .sessionManagement()
          .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
          .and()
          .csrf()
          .disable()
          .addFilterBefore(
              new BasicAuthenticationFilter(
                  new BasicAuthenticationManager(attributeAggregationUserName, attributeAggregationPassword)),
              BasicAuthenticationFilter.class
          )
          .authorizeRequests()
          .antMatchers("/v2/ResourceType", "/v2/Me", "/v2/Schema").access("#oauth2.hasScope('attribute-aggregation')")
          .antMatchers("/v2/query").access("#oauth2.hasScope('saml-attribute-query')")
          .antMatchers("/attribute/**").hasRole("ADMIN")
          .antMatchers("/**").hasRole("USER");
    }

    @Override
    public void configure(ResourceServerSecurityConfigurer resources) throws Exception {
      //if we run stateless, then only oauth2 is allowed to populate the security context and this is not what we want
      resources
          .resourceId("attribute-aggregator")
          .stateless(false)
          .tokenServices(resourceServerTokenServices())
          .tokenExtractor(tokenExtractor());
    }

    private DecisionResourceServerTokenServices resourceServerTokenServices() {
      CompositeDecisionResourceServerTokenServices tokenServices = new CompositeDecisionResourceServerTokenServices(
          Arrays.asList(oidcResourceServerTokenServices(), authzResourceServerTokenServices())
      );
      return checkTokenCache ?
          new CachedRemoteTokenServices(tokenServices, checkTokenCacheDurationMilliseconds, checkTokenCacheDurationMilliseconds) :
          tokenServices;
    }

    private DecisionResourceServerTokenServices oidcResourceServerTokenServices() {
      return new OidcRemoteTokenServices(oidcCheckTokenEndpointUrl, oidcCheckTokenClientId, oidcCheckTokenSecret);
    }

    private DecisionResourceServerTokenServices authzResourceServerTokenServices() {
      final DefaultAccessTokenConverter accessTokenConverter = new DefaultAccessTokenConverter();
      accessTokenConverter.setUserTokenConverter(new AuthzSchacHomeAwareUserAuthenticationConverter());
      return new AuthzResourceServerTokenServices(authzCheckTokenClientId, authzCheckTokenSecret, authzCheckTokenEndpointUrl, accessTokenConverter);
    }

    /*
     * Explicitly deny other means of supplying oauth token than "bearer"
     */
    private TokenExtractor tokenExtractor() {
      return new BearerTokenExtractor() {
        protected String extractToken(HttpServletRequest request) {
          // only check the header...
          return extractHeaderToken(request);
        }
      };
    }
  }

}
