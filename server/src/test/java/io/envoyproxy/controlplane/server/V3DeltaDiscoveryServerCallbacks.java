package io.envoyproxy.controlplane.server;


import io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class V3DeltaDiscoveryServerCallbacks implements DiscoveryServerCallbacks  {

  private static final Logger LOGGER = LoggerFactory.getLogger(V3DeltaDiscoveryServerCallbacks.class);

  private final CountDownLatch onStreamOpenLatch;
  private final CountDownLatch onStreamRequestLatch;
  private final CountDownLatch onStreamResponseLatch;

  /**
   * Returns an implementation of DiscoveryServerCallbacks that throws if it sees a v2 request,
   * and counts down on provided latches in response to certain events.
   *
   * @param onStreamOpenLatch latch to call countDown() on when a v3 stream is opened.
   * @param onStreamRequestLatch latch to call countDown() on when a v3 request is seen.
   * @param onStreamResponseLatch latch to call countDown() on when a v3 response is seen.
   */
  public V3DeltaDiscoveryServerCallbacks(CountDownLatch onStreamOpenLatch,
      CountDownLatch onStreamRequestLatch, CountDownLatch onStreamResponseLatch) {
    this.onStreamOpenLatch = onStreamOpenLatch;
    this.onStreamRequestLatch = onStreamRequestLatch;
    this.onStreamResponseLatch = onStreamResponseLatch;
  }

  @Override
  public void onStreamOpen(long streamId, String typeUrl) {
    LOGGER.info("onStreamOpen called");
    onStreamOpenLatch.countDown();
  }

  @Override
  public void onV2StreamRequest(long streamId,
      io.envoyproxy.envoy.api.v2.DiscoveryRequest request) {
    throw new IllegalStateException("Unexpected v2 request in v3 test");
  }

  @Override
  public void onV3StreamRequest(long streamId, DiscoveryRequest request) {
    throw new IllegalStateException("Unexpected stream request");

  }

  @Override
  public void onV2StreamDeltaRequest(long streamId, DeltaDiscoveryRequest request) {
    throw new IllegalStateException("Unexpected v2 request in v3 test");
  }

  @Override
  public void onV3StreamDeltaRequest(long streamId,
      io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest request) {
    LOGGER.info("Got a v3StreamRequest");
    onStreamRequestLatch.countDown();
  }

  @Override
  public void onStreamResponse(long streamId,
      io.envoyproxy.envoy.api.v2.DiscoveryRequest request,
      io.envoyproxy.envoy.api.v2.DiscoveryResponse response) {
    throw new IllegalStateException("Unexpected v2 response in v3 test");
  }

  @Override
  public void onV3StreamResponse(long streamId, DiscoveryRequest request,
      DiscoveryResponse response) {
    LOGGER.info("Got a v3StreamResponse");
    onStreamResponseLatch.countDown();
  }
}

