package io.envoyproxy.controlplane.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;

import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.netty.NettyServerBuilder;
import io.restassured.http.ContentType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

public class V3DiscoveryServerXdsDeltaResourcesIT {

  private static final Logger LOGGER = LoggerFactory
      .getLogger(V3DiscoveryServerXdsDeltaResourcesIT.class);

  private static final String CONFIG = "envoy/xds.v3.delta.config.yaml";
  private static final String GROUP = "key";
  private static final Integer LISTENER_PORT = 10000;

  private static final CountDownLatch onStreamOpenLatch = new CountDownLatch(1);
  private static final CountDownLatch onStreamRequestLatch = new CountDownLatch(1);

  private static StringBuffer nonce = new StringBuffer();
  private static StringBuffer errorDetails = new StringBuffer();

  private static final SimpleCache<String> cache = new SimpleCache<>(new NodeGroup<String>() {
    @Override
    public String hash(Node node) {
      throw new IllegalStateException("Unexpected v2 request in v3 test");
    }

    @Override
    public String hash(io.envoyproxy.envoy.config.core.v3.Node node) {
      return GROUP;
    }
  });

  private static final NettyGrpcServerRule XDS = new NettyGrpcServerRule() {
    @Override
    protected void configureServerBuilder(NettyServerBuilder builder) {

      final DiscoveryServerCallbacks callbacks =
          new V3DeltaDiscoveryServerCallbacks(onStreamOpenLatch, onStreamRequestLatch, nonce,
              errorDetails);

      Snapshot snapshot = V3TestSnapshots.createSnapshotNoEds(false,
          true,
          "upstream",
          UPSTREAM.ipAddress(),
          EchoContainer.PORT,
          "listener0",
          LISTENER_PORT,
          "route0",
          "1");
      LOGGER.info("snapshot={}", snapshot);
      cache.setSnapshot(
          GROUP,
          snapshot
      );

      V3DiscoveryServer server = new V3DiscoveryServer(callbacks, cache);

      builder.addService(server.getRouteDiscoveryServiceImpl());
      builder.addService(server.getListenerDiscoveryServiceImpl());
      builder.addService(server.getEndpointDiscoveryServiceImpl());
      builder.addService(server.getClusterDiscoveryServiceImpl());
      builder.addService(server.getSecretDiscoveryServiceImpl());
    }
  };

  private static final Network NETWORK = Network.newNetwork();

  private static final EnvoyContainer ENVOY = new EnvoyContainer(CONFIG,
      () -> XDS.getServer().getPort())
      .withExposedPorts(LISTENER_PORT)
      .withNetwork(NETWORK);

  private static final EchoContainer UPSTREAM = new EchoContainer()
      .withNetwork(NETWORK)
      .withNetworkAliases("upstream");

  @ClassRule
  public static final RuleChain RULES = RuleChain.outerRule(UPSTREAM)
      .around(XDS)
      .around(ENVOY);

  @Test
  public void validateTestRequestToEchoServerViaEnvoy() throws InterruptedException {
    assertThat(onStreamOpenLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to open ADS stream");

    assertThat(onStreamRequestLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to receive ADS request");

    // there is no onStreamResponseLatch because V3DiscoveryServer doesn't call the callbacks
    // when responding to a delta request

    String baseUri = String
        .format("http://%s:%d", ENVOY.getContainerIpAddress(), ENVOY.getMappedPort(LISTENER_PORT));

    await().atMost(5, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(
        () -> given().baseUri(baseUri).contentType(ContentType.TEXT)
            .when().get("/")
            .then().statusCode(200)
            .and().body(containsString(UPSTREAM.response)));

    // we'll get three 0 nonces as this relates to the first version of resource state
    // and check that errorDetails is empty
    assertThat(nonce.toString()).isEqualTo("000");
    assertThat(errorDetails.toString()).isEqualTo("");

    // now write a new snapshot, with the only change being an update
    // to the listener name, wait for a few seconds for envoy to pick it up, and
    // check that the nonce envoy most recently ACK'd is "1"
    Snapshot snapshot = V3TestSnapshots.createSnapshotNoEds(false,
        true,
        "upstream",
        UPSTREAM.ipAddress(),
        EchoContainer.PORT,
        "listener1",
        LISTENER_PORT,
        "route0",
        "2");
    LOGGER.info("snapshot={}", snapshot);
    cache.setSnapshot(
        GROUP,
        snapshot
    );

    // after the update, we've changed listener1, so will get a new nonce
    await().atMost(3, TimeUnit.SECONDS).untilAsserted(
        () -> {
          assertThat(nonce.toString()).isEqualTo("0001");
          assertThat(errorDetails.toString()).isEqualTo("");
        }
    );
  }

  @Test
  public void validateNewSnapshotVersionButSameUnderlyingResourcesDoesNotTriggerUpdate()
      throws InterruptedException {
    assertThat(onStreamOpenLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to open ADS stream");

    assertThat(onStreamRequestLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to receive ADS request");

    // there is no onStreamResponseLatch because V3DiscoveryServer doesn't call the callbacks
    // when responding to a delta request

    String baseUri = String
        .format("http://%s:%d", ENVOY.getContainerIpAddress(), ENVOY.getMappedPort(LISTENER_PORT));

    await().atMost(5, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(
        () -> given().baseUri(baseUri).contentType(ContentType.TEXT)
            .when().get("/")
            .then().statusCode(200)
            .and().body(containsString(UPSTREAM.response)));

    // we'll count three nonces of "0" and make sure we've gotten no errors
    assertThat(nonce.toString()).isEqualTo("000");
    assertThat(errorDetails.toString()).isEqualTo("");

    // now write a new snapshot, with no changes to the params we pass in
    // but update version. This being a Delta request, this version doesn't
    // really matter, and Envoy should not receive a spontaneous update
    // because the hash of the resources will be the same
    Snapshot snapshot = V3TestSnapshots.createSnapshotNoEds(false,
        true,
        "upstream",
        UPSTREAM.ipAddress(),
        EchoContainer.PORT,
        "listener0",
        LISTENER_PORT,
        "route0",
        "2");
    LOGGER.info("snapshot={}", snapshot);
    cache.setSnapshot(
        GROUP,
        snapshot
    );

    await().atMost(3, TimeUnit.SECONDS).untilAsserted(
        () -> {
          assertThat(nonce.toString()).isEqualTo("000");
          assertThat(errorDetails.toString()).isEqualTo("");
        }
    );
  }

  @AfterClass
  public static void after() throws Exception {
    ENVOY.close();
    UPSTREAM.close();
    NETWORK.close();
  }
}
