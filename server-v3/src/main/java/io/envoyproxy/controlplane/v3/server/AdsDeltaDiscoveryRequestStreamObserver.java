package io.envoyproxy.controlplane.v3.server;

import static io.envoyproxy.controlplane.v3.server.DiscoveryServer.ANY_TYPE_URL;

import io.envoyproxy.controlplane.v3.cache.DeltaWatch;
import io.envoyproxy.controlplane.v3.cache.Resources;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * {@code AdsDiscoveryRequestStreamObserver} is an implementation of {@link DiscoveryRequestStreamObserver} tailored for
 * ADS streams, which handle multiple watches for all TYPE_URLS.
 */
public class AdsDeltaDiscoveryRequestStreamObserver extends DeltaDiscoveryRequestStreamObserver {
  private final ConcurrentMap<String, DeltaWatch> watches;
  private final ConcurrentMap<String, LatestDeltaDiscoveryResponse> latestResponse;
  private final ConcurrentMap<String, Map<String, String>> trackedResourceMap;
  private final ConcurrentMap<String, Set<String>> pendingResourceMap;

  AdsDeltaDiscoveryRequestStreamObserver(StreamObserver<DeltaDiscoveryResponse> responseObserver,
                                         long streamId,
                                         Executor executor,
                                         DiscoveryServer discoveryServer) {
    super(ANY_TYPE_URL, responseObserver, streamId, executor, discoveryServer);
    this.watches = new ConcurrentHashMap<>(Resources.TYPE_URLS.size());
    this.latestResponse = new ConcurrentHashMap<>(Resources.TYPE_URLS.size());
    this.trackedResourceMap = new ConcurrentHashMap<>(Resources.TYPE_URLS.size());
    this.pendingResourceMap = new ConcurrentHashMap<>(Resources.TYPE_URLS.size());
  }

  @Override
  public void onNext(DeltaDiscoveryRequest request) {
    if (request.getTypeUrl().isEmpty()) {
      closeWithError(
          Status.UNKNOWN
              .withDescription(String.format("[%d] type URL is required for ADS", streamId))
              .asRuntimeException());

      return;
    }

    super.onNext(request);
  }

  @Override
  void cancel() {
    watches.values().forEach(DeltaWatch::cancel);
  }

  @Override
  boolean ads() {
    return true;
  }

  @Override
  LatestDeltaDiscoveryResponse latestResponse(String typeUrl) {
    return latestResponse.get(typeUrl);
  }

  @Override
  void setLatestResponse(String typeUrl, LatestDeltaDiscoveryResponse response) {
    latestResponse.put(typeUrl, response);
    if (typeUrl.equals(Resources.CLUSTER_TYPE_URL)) {
      hasClusterChanged = true;
    } else if (typeUrl.equals(Resources.ENDPOINT_TYPE_URL)) {
      hasClusterChanged = false;
    }
  }

  @Override
  Map<String, String> resourceVersions(String typeUrl) {
    return trackedResourceMap.getOrDefault(typeUrl, Collections.emptyMap());
  }

  @Override
  Set<String> pendingResources(String typeUrl) {
    return pendingResourceMap.getOrDefault(typeUrl, Collections.emptySet());
  }

  @Override
  boolean isWildcard(String typeUrl) {
    return typeUrl.equals(Resources.CLUSTER_TYPE_URL)
        || typeUrl.equals(Resources.LISTENER_TYPE_URL);
  }

  @Override
  void updateTrackedResources(String typeUrl,
                              Map<String, String> resourcesVersions,
                              List<String> resourceNamesSubscribe,
                              List<String> removedResources,
                              List<String> resourceNamesUnsubscribe) {

    Map<String, String> trackedResources = trackedResourceMap.computeIfAbsent(typeUrl, s -> new HashMap<>());
    Set<String> pendingResources = pendingResourceMap.computeIfAbsent(typeUrl, s -> new HashSet<>());
    resourcesVersions.forEach((k, v) -> {
      trackedResources.put(k, v);
      pendingResources.remove(k);
    });
    pendingResources.addAll(resourceNamesSubscribe);
    removedResources.forEach(trackedResources::remove);
    resourceNamesUnsubscribe.forEach(s -> {
      trackedResources.remove(s);
      pendingResources.remove(s);
    });
  }

  @Override
  void computeWatch(String typeUrl, Supplier<DeltaWatch> watchCreator) {
    watches.compute(typeUrl, (s, watch) -> {
      if (watch != null) {
        watch.cancel();
      }

      return watchCreator.get();
    });
  }
}
