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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
      discoverySever.callbacks.forEach(cb -> cb.onStreamDeltaRequest(streamId, node, completeRequest));
    } catch (RequestException e) {
      closeWithError(e);
      return;
    }

    LatestDeltaDiscoveryResponse latestDiscoveryResponse = latestResponse(requestTypeUrl);
    String resourceNonce;
    String version;
    if (latestDiscoveryResponse == null) {
      resourceNonce = null;
      version = "";
    } else {
      resourceNonce = latestDiscoveryResponse.nonce();
      version = latestDiscoveryResponse.version();
    }

    if (!completeRequest.getResponseNonce().isEmpty()) {
      if (!completeRequest.getResponseNonce().equals(resourceNonce)) {
        // envoy is acking a previous version, we should wait for the latest response
        return;
      }

      // envoy is acking o nacking previous response
      if (!completeRequest.hasErrorDetail()) {
        // update tracked resources only if envoy has acked
        updateTrackedResources(requestTypeUrl,
            latestDiscoveryResponse.resourceVersions(),
            request.getResourceNamesSubscribeList(),
            latestDiscoveryResponse.removedResources(),
            request.getResourceNamesUnsubscribeList());
      }
    } else {
      // envoy is requesting new resources
      // initialResourceVersionsMap return empty map if not first request
      updateTrackedResources(requestTypeUrl,
          request.getInitialResourceVersionsMap(),
          request.getResourceNamesSubscribeList(),
          Collections.emptyList(),
          request.getResourceNamesUnsubscribeList());
    }

    // schedule a watch creation to allow merging subsequent resource requests
    executor.execute(() -> {
      ScheduledFuture<?> handle = handle(requestTypeUrl);
      if (handle != null) {
        handle.cancel(false);
      }
      setHandle(requestTypeUrl, executor.schedule(() ->
          computeWatch(requestTypeUrl, () -> discoverySever.configWatcher.createDeltaWatch(
              completeRequest,
              version,
              resourceVersions(requestTypeUrl),
              pendingResources(requestTypeUrl),
              isWildcard(requestTypeUrl),
              r -> executor.execute(() -> send(r, requestTypeUrl)),
              hasClusterChanged
          )), 20, TimeUnit.MILLISECONDS));
    });
  }

  abstract ScheduledFuture<?> handle(String typeUrl);

  abstract void setHandle(String typeUrl, ScheduledFuture<?> handle);

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
        cb.onStreamDeltaResponse(streamId, node, response.request(), discoveryResponse));

    // Store the latest response *before* we send the response. This ensures that by the time the request
    // is processed the map is guaranteed to be updated. Doing it afterwards leads to a race conditions
    // which may see the incoming request arrive before the map is updated, failing the nonce check erroneously.
    setLatestResponse(
        typeUrl,
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

  abstract Map<String, String> resourceVersions(String typeUrl);

  abstract Set<String> pendingResources(String typeUrl);

  abstract boolean isWildcard(String typeUrl);

  abstract void updateTrackedResources(String typeUrl,
                                       Map<String, String> resourcesVersions,
                                       List<String> resourceNamesSubscribe,
                                       List<String> removedResources,
                                       List<String> resourceNamesUnsubscribe);

  abstract void computeWatch(String typeUrl, Supplier<DeltaWatch> watchCreator);
}
