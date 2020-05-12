package io.envoyproxy.controlplane.v3.server;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.collect.ImmutableSet;
import io.envoyproxy.controlplane.v3.cache.DeltaResponse;
import io.envoyproxy.controlplane.v3.cache.DeltaWatch;
import io.envoyproxy.controlplane.v3.cache.Resources;
import io.envoyproxy.controlplane.v3.server.exception.RequestException;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.Resource;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code DiscoveryRequestStreamObserver} provides the base implementation for XDS stream handling.
 */
public abstract class DeltaDiscoveryRequestStreamObserver implements StreamObserver<DeltaDiscoveryRequest> {
  private static final AtomicLongFieldUpdater<DeltaDiscoveryRequestStreamObserver> streamNonceUpdater =
      AtomicLongFieldUpdater.newUpdater(DeltaDiscoveryRequestStreamObserver.class, "streamNonce");
  private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryServer.class);

  final long streamId;
  private final String defaultTypeUrl;
  private final StreamObserver<DeltaDiscoveryResponse> responseObserver;
  private final Executor executor;
  private final DiscoveryServer discoverySever;
  volatile boolean hasClusterChanged;
  private volatile long streamNonce;
  private volatile boolean isClosing;
  private Node node;
  private boolean isWildcard;

  DeltaDiscoveryRequestStreamObserver(String defaultTypeUrl,
                                      StreamObserver<DeltaDiscoveryResponse> responseObserver,
                                      long streamId,
                                      Executor executor,
                                      DiscoveryServer discoveryServer) {
    this.defaultTypeUrl = defaultTypeUrl;
    this.responseObserver = responseObserver;
    this.streamId = streamId;
    this.executor = executor;
    this.streamNonce = 0;
    this.discoverySever = discoveryServer;
    this.hasClusterChanged = false;
  }

  @Override
  public void onNext(DeltaDiscoveryRequest request) {
    if (node == null && request.hasNode()) {
      node = request.getNode();
    }
    String requestTypeUrl = request.getTypeUrl().isEmpty() ? defaultTypeUrl : request.getTypeUrl();
    if (node == null && request.hasNode()) {
      node = request.getNode();
      isWildcard = request.getResourceNamesSubscribeCount() == 0
          && (requestTypeUrl.equals(Resources.CLUSTER_TYPE_URL) || requestTypeUrl.equals(Resources.LISTENER_TYPE_URL));
    }
    String nonce = request.getResponseNonce();

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("[{}] request {}[{}] with nonce {} from versions {}",
          streamId,
          requestTypeUrl,
          String.join(", ", request.getResourceNamesSubscribeList()),
          nonce,
          request.getInitialResourceVersionsMap());
    }

    try {
      discoverySever.callbacks.forEach(cb -> cb.onStreamDeltaRequest(streamId, node, request));
    } catch (RequestException e) {
      closeWithError(e);
      return;
    }

    LatestDeltaDiscoveryResponse latestDiscoveryResponse = latestResponse(requestTypeUrl);
    String resourceNonce = latestDiscoveryResponse == null ? null : latestDiscoveryResponse.nonce();

    if (isNullOrEmpty(resourceNonce) || resourceNonce.equals(nonce)) {
      if (!request.hasErrorDetail()) {
        if (latestDiscoveryResponse == null) {
          updateTrackedResources(requestTypeUrl, request.getInitialResourceVersionsMap(), ImmutableSet.of());
        } else {
          updateTrackedResources(requestTypeUrl,
              latestDiscoveryResponse.resourceVersions(),
              latestDiscoveryResponse.removedResources());
        }
      }

      computeWatch(requestTypeUrl, () -> discoverySever.configWatcher.createDeltaWatch(
          request,
          trackedResources(requestTypeUrl),
          r -> executor.execute(() -> send(r, requestTypeUrl))
      ));
    }
  }

  @Override
  public void onError(Throwable t) {
    if (!Status.fromThrowable(t).getCode().equals(Status.CANCELLED.getCode())) {
      LOGGER.error("[{}] stream closed with error", streamId, t);
    }

    try {
      discoverySever.callbacks.forEach(cb -> cb.onStreamCloseWithError(streamId, defaultTypeUrl, t));
      closeWithError(Status.fromThrowable(t).asException());
    } finally {
      cancel();
    }
  }

  @Override
  public void onCompleted() {
    LOGGER.debug("[{}] stream closed", streamId);

    try {
      discoverySever.callbacks.forEach(cb -> cb.onStreamClose(streamId, defaultTypeUrl));
      synchronized (responseObserver) {
        if (!isClosing) {
          isClosing = true;
          responseObserver.onCompleted();
        }
      }
    } finally {
      cancel();
    }
  }

  void onCancelled() {
    LOGGER.info("[{}] stream cancelled", streamId);
    cancel();
  }

  void closeWithError(Throwable exception) {
    synchronized (responseObserver) {
      if (!isClosing) {
        isClosing = true;
        responseObserver.onError(exception);
      }
    }
    cancel();
  }

  private void send(DeltaResponse response, String typeUrl) {
    String nonce = Long.toString(streamNonceUpdater.getAndIncrement(this));

    // TODO: Collection<Any> resources = discoverySever.protoResourcesSerializer.serialize(response.resources());
    DeltaDiscoveryResponse discoveryResponse = DeltaDiscoveryResponse.newBuilder()
        .setSystemVersionInfo(response.version())
        .addAllResources(response.resources())
        .addAllRemovedResources(response.removedResources())
        .setTypeUrl(typeUrl)
        .setNonce(nonce)
        .build();

    LOGGER.debug("[{}] response {} with nonce {} version {}", streamId, typeUrl, nonce, response.version());

    discoverySever.callbacks.forEach(cb -> cb.onStreamDeltaResponse(streamId, node, response.request(), discoveryResponse));

    // Store the latest response *before* we send the response. This ensures that by the time the request
    // is processed the map is guaranteed to be updated. Doing it afterwards leads to a race conditions
    // which may see the incoming request arrive before the map is updated, failing the nonce check erroneously.
    setLatestResponse(
        typeUrl,
        LatestDeltaDiscoveryResponse.create(
            nonce,
            response.resources().stream().collect(Collectors.toMap(Resource::getName, Resource::getVersion)),
            response.removedResources()
        )
    );
    synchronized (responseObserver) {
      if (!isClosing) {
        try {
          responseObserver.onNext(discoveryResponse);
        } catch (StatusRuntimeException e) {
          if (!Status.CANCELLED.getCode().equals(e.getStatus().getCode())) {
            throw e;
          }
        }
      }
    }
  }

  abstract void cancel();

  abstract boolean ads();

  abstract LatestDeltaDiscoveryResponse latestResponse(String typeUrl);

  abstract void setLatestResponse(String typeUrl, LatestDeltaDiscoveryResponse response);

  abstract Map<String, String> trackedResources(String typeUrl);

  abstract void updateTrackedResources(String typeUrl,
                                       Map<String, String> resourceVersions,
                                       Set<String> removedResources);

  abstract void computeWatch(String typeUrl, Supplier<DeltaWatch> watchCreator);
}
