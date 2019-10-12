package aa.aggregators.eduid;

import aa.model.ArpAggregationRequest;
import aa.model.AttributeAuthorityConfiguration;
import aa.model.RequiredInputAttribute;
import aa.model.UserAttribute;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

import static aa.aggregators.AttributeAggregator.EDU_PERSON_PRINCIPAL_NAME;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.Collections.singletonList;
import static org.apache.commons.codec.binary.Base64.encodeBase64String;
import static org.junit.Assert.assertEquals;

public class EduIDAttributeAggregatorTest {

    private ObjectMapper objectMapper = new ObjectMapper();

    private ArpAggregationRequest arpAggregationRequest;

    private EduIDAttributeAggregator subject;

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(8889);

    @Before
    public void before() throws IOException {
        AttributeAuthorityConfiguration configuration = new AttributeAuthorityConfiguration("eduid");
        configuration.setUser("user");
        configuration.setPassword("password");
        configuration.setEndpoint("http://localhost:8889/attribute_aggregation");
        configuration.setRequiredInputAttributes(singletonList(new RequiredInputAttribute(EDU_PERSON_PRINCIPAL_NAME)));
        subject = new EduIDAttributeAggregator(configuration);

        arpAggregationRequest = objectMapper.readValue(new ClassPathResource("eduid/arp_aggregation_request.json").getInputStream(),
                ArpAggregationRequest.class);
    }

    @Test
    public void aggregate() throws IOException {
        stubForEduId(read("eduid/attributes.json"));
        List<UserAttribute> userAttributes = subject.aggregate(
                arpAggregationRequest.getUserAttributes(),
                arpAggregationRequest.getArpAttributes());
        assertEquals(10, userAttributes.size());
        userAttributes.forEach(userAttribute -> assertEquals("eduid", userAttribute.getSource()));
    }

    @Test
    public void filterInvalidResponses() {
        List<UserAttribute> userAttributes = subject.filterInvalidResponses(singletonList(new UserAttribute("nope", singletonList("value"))));
        assertEquals(1, userAttributes.size());
    }

    private void stubForEduId(String response) {
        stubFor(get(urlPathEqualTo("/attribute_aggregation"))
                .withHeader("Authorization", equalTo("Basic " + encodeBase64String("user:password".getBytes())))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(response)));
    }

    private String read(String path) throws IOException {
        return IOUtils.toString(new ClassPathResource(path).getInputStream(), Charset.defaultCharset());
    }

}