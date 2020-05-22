package io.envoyproxy.controlplane.v3.server;

import io.envoyproxy.controlplane.v3.cache.DeltaWatch;
import io.envoyproxy.controlplane.v3.cache.Resources;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

/**
 * {@code XdsDiscoveryRequestStreamObserver} is a lightweight implementation of {@link DiscoveryRequestStreamObserver}
 * tailored for non-ADS streams which handle a single watch.
 */
public class XdsDeltaDiscoveryRequestStreamObserver extends DeltaDiscoveryRequestStreamObserver {
  // tracked is only used in the same thread so it need not be volatile
  private final Map<String, String> trackedResources;
  private final Set<String> pendingResources;
  private final boolean isWildcard;
  private volatile DeltaWatch watch;
  private volatile LatestDeltaDiscoveryResponse latestDiscoveryResponse;
  private ScheduledFuture<?> handle;

  XdsDeltaDiscoveryRequestStreamObserver(String defaultTypeUrl,
                                         StreamObserver<DeltaDiscoveryResponse> responseObserver,
                                         long streamId,
                                         ScheduledExecutorService executor,
                                         DiscoveryServer discoveryServer) {
    super(defaultTypeUrl, responseObserver, streamId, executor, discoveryServer);
    this.trackedResources = new HashMap<>();
    this.pendingResources = new HashSet<>();
    this.isWildcard = defaultTypeUrl.equals(Resources.CLUSTER_TYPE_URL)
        || defaultTypeUrl.equals(Resources.LISTENER_TYPE_URL)
        || defaultTypeUrl.equals(Resources.SCOPED_ROUTE_TYPE_URL);
  }

  @Override
  public void onNext(DeltaDiscoveryRequest request) {
    super.onNext(request);
  }

  @Override
  ScheduledFuture<?> handle(String typeUrl) {
    return handle;
  }

  @Override
  void setHandle(String typeUrl, ScheduledFuture<?> handle) {
    this.handle = handle;
  }

  @Override
  void cancel() {
    if (watch != null) {
      watch.cancel();
    }
  }

  @Override
  boolean ads() {
    return false;
  }

  @Override
  LatestDeltaDiscoveryResponse latestResponse(String typeUrl) {
    return latestDiscoveryResponse;
  }

  @Override
  void setLatestResponse(String typeUrl, LatestDeltaDiscoveryResponse response) {
    latestDiscoveryResponse = response;
  }

  @Override
  Map<String, String> resourceVersions(String typeUrl) {
    return trackedResources;
  }

  @Override
  Set<String> pendingResources(String typeUrl) {
    return pendingResources;
  }

  @Override
  boolean isWildcard(String typeUrl) {
    return isWildcard;
  }

  @Override
  void updateTrackedResources(String typeUrl,
                              Map<String, String> resourcesVersions,
                              List<String> resourceNamesSubscribe,
                              List<String> removedResources,
                              List<String> resourceNamesUnsubscribe) {

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
    cancel();
    watch = watchCreator.get();
  }
}
