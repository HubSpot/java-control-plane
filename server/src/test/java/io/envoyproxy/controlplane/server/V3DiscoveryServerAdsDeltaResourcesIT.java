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
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

public class V3DiscoveryServerAdsDeltaResourcesIT {
  private static final Logger LOGGER = LoggerFactory.getLogger(V3DiscoveryServerAdsDeltaResourcesIT.class);

  private static final String CONFIG = "envoy/ads.v3.delta.config.yaml";
  private static final String GROUP = "key";
  private static final Integer LISTENER_PORT = 10000;

  private static final CountDownLatch onStreamOpenLatch = new CountDownLatch(1);
  private static final CountDownLatch onStreamRequestLatch = new CountDownLatch(1);

  private static  StringBuffer nonce = new StringBuffer();

  private static final SimpleCache<String> cache = new SimpleCache<>(new NodeGroup<String>() {
    @Override public String hash(Node node) {
      throw new IllegalStateException("Unexpected v2 request in v3 test");
    }

    @Override public String hash(io.envoyproxy.envoy.config.core.v3.Node node) {
      return GROUP;
    }
  });

  private static final NettyGrpcServerRule ADS = new NettyGrpcServerRule() {
    @Override
    protected void configureServerBuilder(NettyServerBuilder builder) {

      final DiscoveryServerCallbacks callbacks =
          new V3DeltaDiscoveryServerCallbacks(onStreamOpenLatch, onStreamRequestLatch, nonce);

      Snapshot snapshot = V3TestSnapshots.createSnapshot(true,
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

      builder.addService(server.getAggregatedDiscoveryServiceImpl());
    }
  };

  private static final Network NETWORK = Network.newNetwork();

  private static final EnvoyContainer ENVOY = new EnvoyContainer(CONFIG, () -> ADS.getServer().getPort())
      .withExposedPorts(LISTENER_PORT)
      .withNetwork(NETWORK);

  private static final EchoContainer UPSTREAM = new EchoContainer()
      .withNetwork(NETWORK)
      .withNetworkAliases("upstream");

  @ClassRule
  public static final RuleChain RULES = RuleChain.outerRule(UPSTREAM)
      .around(ADS)
      .around(ENVOY);

  @Test
  public void validateTestRequestToEchoServerViaEnvoy() throws InterruptedException {
    assertThat(onStreamOpenLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to open ADS stream");

    assertThat(onStreamRequestLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to receive ADS request");

    // there is no onStreamResponseLatch because V3DiscoveryServer doesn't call the callbacks
    // when responding to a delta request

    String baseUri = String.format("http://%s:%d", ENVOY.getContainerIpAddress(), ENVOY.getMappedPort(LISTENER_PORT));

    await().atMost(5, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(
        () -> given().baseUri(baseUri).contentType(ContentType.TEXT)
            .when().get("/")
            .then().statusCode(200)
            .and().body(containsString(UPSTREAM.response)));

    // basically the nonces will count up from 0 to 3 as envoy receives more resources
    assertThat(nonce.toString()).isEqualTo("0123");

    // now write a new snapshot, with the only change being an update
    // to the listener name, wait for a few seconds for envoy to pick it up, and
    // check that the nonc envoy most recently ACK'd is "4"
    Snapshot snapshot = V3TestSnapshots.createSnapshot(true,
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

    TimeUnit.SECONDS.sleep(3);
    assertThat(nonce.toString()).isEqualTo("01234");
  }

  @After
  public void after() throws Exception {
    ENVOY.close();
    UPSTREAM.close();
    NETWORK.close();
  }
}
