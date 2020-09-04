package io.envoyproxy.controlplane.v3.server;

import com.google.common.collect.ImmutableMap;
import io.envoyproxy.controlplane.v3.cache.DeltaResponse;
import io.envoyproxy.controlplane.v3.cache.DeltaWatch;
import io.envoyproxy.controlplane.v3.server.exception.RequestException;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.Resource;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
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
  private final ScheduledExecutorService executor;
  private final DiscoveryServer discoverySever;
  volatile boolean hasClusterChanged;
  private volatile long streamNonce;
  private volatile boolean isClosing;
  private Node node;

  DeltaDiscoveryRequestStreamObserver(String defaultTypeUrl,
                                      StreamObserver<DeltaDiscoveryResponse> responseObserver,
                                      long streamId,
                                      ScheduledExecutorService executor,
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
    String requestTypeUrl = request.getTypeUrl().isEmpty() ? defaultTypeUrl : request.getTypeUrl();
    if (node == null && request.hasNode()) {
      node = request.getNode();
    }

    final DeltaDiscoveryRequest completeRequest;
    if (!request.hasNode() || request.getTypeUrl().isEmpty()) {
      completeRequest = request.toBuilder()
          .setTypeUrl(requestTypeUrl)
          .setNode(node)
          .build();
    } else {
      completeRequest = request;
    }

    String nonce = completeRequest.getResponseNonce();

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("[{}] request {}[{}] with nonce {} from versions {}",
          streamId,
          requestTypeUrl,
          String.join(", ", completeRequest.getResourceNamesSubscribeList()),
          nonce,
          completeRequest.getInitialResourceVersionsMap());
    }

    try {
      discoverySever.callbacks.forEach(cb -> cb.onStreamDeltaRequest(streamId, completeRequest));
    } catch (RequestException e) {
      closeWithError(e);
      return;
    }

    final String version;
    if (latestVersion(requestTypeUrl) == null) {
      version = "";
    } else {
      version = latestVersion(requestTypeUrl);
    }

    // always update subscriptions
    updateSubscriptions(requestTypeUrl,
        request.getResourceNamesSubscribeList(),
        request.getResourceNamesUnsubscribeList());

    if (!completeRequest.getResponseNonce().isEmpty()) {
      // envoy is replying to a response we sent, get and clear respective response
      LatestDeltaDiscoveryResponse response = clearResponse(requestTypeUrl, completeRequest.getResponseNonce());
      if (!completeRequest.hasErrorDetail()) {
        // if envoy has acked, update tracked resources
        // from the corresponding response
        updateTrackedResources(requestTypeUrl,
            response.resourceVersions(),
            response.removedResources());
      }
    }

    // if nonce is empty, envoy is only requesting new resources or this is a new connection,
    // in either case we have already updated the subscriptions

    if (responseCount(requestTypeUrl) == 0) {
      // we should only create watches when there's no pending ack, this ensures
      // we don't have two outstanding responses
      computeWatch(requestTypeUrl, () -> discoverySever.configWatcher.createDeltaWatch(
          completeRequest,
          version,
          resourceVersions(requestTypeUrl),
          pendingResources(requestTypeUrl),
          isWildcard(requestTypeUrl),
          r -> executor.execute(() -> send(r, requestTypeUrl)),
          hasClusterChanged
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

    DeltaDiscoveryResponse discoveryResponse = DeltaDiscoveryResponse.newBuilder()
        .setSystemVersionInfo(response.version())
        .addAllResources(response.resources()
            .entrySet()
            .stream()
            .map(entry -> Resource.newBuilder()
                .setName(entry.getKey())
                .setResource(discoverySever.protoResourcesSerializer.serialize(entry.getValue().resource()))
                .setVersion(entry.getValue().version())
                .build())
            .collect(Collectors.toList()))
        .addAllRemovedResources(response.removedResources())
        .setTypeUrl(typeUrl)
        .setNonce(nonce)
        .build();

    LOGGER.debug("[{}] response {} with nonce {} version {}", streamId, typeUrl, nonce, response.version());

    discoverySever.callbacks.forEach(cb ->
        cb.onStreamDeltaResponse(streamId, response.request(), discoveryResponse));

    // Store the latest response *before* we send the response. This ensures that by the time the request
    // is processed the map is guaranteed to be updated. Doing it afterwards leads to a race conditions
    // which may see the incoming request arrive before the map is updated, failing the nonce check erroneously.
    setResponse(
        typeUrl,
        nonce,
        LatestDeltaDiscoveryResponse.create(
            nonce,
            response.version(),
            response.resources()
                .entrySet()
                .stream()
                .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, entry -> entry.getValue().version())),
            response.removedResources()
        )
    );
    setLatestVersion(typeUrl, response.version());
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

  abstract void setLatestVersion(String typeUrl, String version);

  abstract String latestVersion(String typeUrl);

  abstract void setResponse(String typeUrl, String nonce, LatestDeltaDiscoveryResponse response);

  abstract LatestDeltaDiscoveryResponse clearResponse(String typeUrl, String nonce);

  abstract int responseCount(String typeUrl);

  abstract Map<String, String> resourceVersions(String typeUrl);

  abstract Set<String> pendingResources(String typeUrl);

  abstract boolean isWildcard(String typeUrl);

  abstract void updateTrackedResources(String typeUrl,
                                       Map<String, String> resourcesVersions,
                                       List<String> removedResources);

  abstract void updateSubscriptions(String typeUrl,
                                    List<String> resourceNamesSubscribe,
                                    List<String> resourceNamesUnsubscribe);

  abstract void computeWatch(String typeUrl, Supplier<DeltaWatch> watchCreator);
}
