package aa.aggregators.orcid;

import aa.model.AttributeAuthorityConfiguration;
import aa.model.RequiredInputAttribute;
import aa.model.UserAttribute;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static aa.aggregators.AttributeAggregator.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static java.util.Collections.singletonList;
import static org.junit.Assert.*;

public class OrcidAttributeAggregatorTest {

  private OrcidAttributeAggregator subject;

  private List<UserAttribute> input = singletonList(new UserAttribute(EDU_PERSON_PRINCIPAL_NAME, singletonList("urn")));

  @Rule
  public WireMockRule wireMockRule = new WireMockRule(8889);

  @Before
  public void before() {
    AttributeAuthorityConfiguration configuration = new AttributeAuthorityConfiguration("orcid");
    configuration.setEndpoint("http://localhost:8889/orcid");
    configuration.setRequiredInputAttributes(singletonList(new RequiredInputAttribute(EDU_PERSON_PRINCIPAL_NAME)));
    subject = new OrcidAttributeAggregator(configuration, "test.surfconext");
  }

  @Test
  public void testGetOrcidHappyFlow() throws Exception {
    List<UserAttribute> userAttributes = getOrcidResponse("orcid/response_succes.json");
    assertUserAttributes(userAttributes);
  }

  private void assertUserAttributes(List<UserAttribute> userAttributes) {
    assertEquals(1, userAttributes.size());
    UserAttribute userAttribute = userAttributes.get(0);
    assertEquals(ORCID, userAttribute.getName());

    List<String> values = userAttribute.getValues();
    assertEquals(1, values.size());
    assertEquals("http://orcid.org/0000-0002-4926-2859", values.get(0));
  }

  @Test
  public void testGetOrcidTrailingX() throws Exception {
    List<UserAttribute> userAttributes = getOrcidResponse("orcid/response_succes.json");
    assertUserAttributes(userAttributes);
  }

  @Test
  public void testGetOrcidWrongInput() throws Exception {
    List<UserAttribute> userAttributes = getOrcidResponse("orcid/response_wrong_orcid.json");
    assertTrue(userAttributes.isEmpty());
  }

  @Test
  public void testGetOrcidEmpty() throws Exception {
    List<UserAttribute> userAttributes = getOrcidResponse("orcid/response_empty.json");
    assertTrue(userAttributes.isEmpty());
  }

  private List<UserAttribute> getOrcidResponse(String jsonFile) throws IOException {
    String response = IOUtils.toString(new ClassPathResource(jsonFile).getInputStream());
    stubFor(get(urlPathEqualTo("/orcid")).willReturn(aResponse().withStatus(200).withBody(response).withHeader("Content-Type", "application/json")));
    return subject.aggregate(input);
  }
}