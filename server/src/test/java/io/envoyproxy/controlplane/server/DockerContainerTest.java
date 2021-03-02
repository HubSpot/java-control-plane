package io.envoyproxy.controlplane.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.restassured.http.ContentType;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

public class DockerContainerTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(DockerContainerTest.class);


  private static final String CONFIG = "envoy/simple.demo.config.yaml";
  private static final String GROUP = "key";

  private static final Network NETWORK = Network.newNetwork();



  private static final EchoContainer ECHO_CONTAINER = new EchoContainer()
      .withNetwork(NETWORK)
      .withNetworkAliases("echo");

  private static final EnvoyContainer ENVOY = new EnvoyContainer(CONFIG, () -> ECHO_CONTAINER
      .getMappedPort(EchoContainer.PORT))
      .withNetwork(NETWORK)
      .withExposedPorts(EnvoyContainer.ADMIN_PORT, EnvoyContainer.LISTENER_PORT);

  @ClassRule
  public static final RuleChain RULES = RuleChain.outerRule(ECHO_CONTAINER).around(ENVOY);

  @Test
  public void testEcho() {
    String baseUriEchoContainer = String.format("http://%s:%d", ECHO_CONTAINER.getContainerIpAddress(), ECHO_CONTAINER.getMappedPort(5678));
    LOGGER.info("baseUriEchoContainer={}", baseUriEchoContainer);
    given().baseUri(baseUriEchoContainer).contentType(ContentType.TEXT)
        .when().get("/")
        .then().statusCode(200)
        .and().body(containsString(ECHO_CONTAINER.response));

    String baseUri = String.format("http://%s:%d", ENVOY.getContainerIpAddress(), ENVOY.getMappedPort(EnvoyContainer.LISTENER_PORT));
    LOGGER.info("baseUri={}", baseUri);
    given().baseUri(baseUri).contentType(ContentType.TEXT)
        .when().get("/")
        .then().statusCode(200)
        .and().body(containsString(ECHO_CONTAINER.response));
    LOGGER.info("done");
  }
}
