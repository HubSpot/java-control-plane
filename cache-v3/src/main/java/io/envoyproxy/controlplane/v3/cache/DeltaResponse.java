package io.envoyproxy.controlplane.v3.cache;

import com.google.auto.value.AutoValue;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import java.util.List;
import java.util.Map;

/**
 * {@code Response} is a data class that contains the response for an assumed configuration type.
 */
@AutoValue
public abstract class DeltaResponse {

  public static DeltaResponse create(DeltaDiscoveryRequest request,
                                     Map<String, SnapshotResource<?>> resources,
                                     List<String> removedResources,
                                     String version) {
    return new AutoValue_DeltaResponse(request, resources, removedResources, version);
  }

  /**
   * Returns the original request associated with the response.
   */
  public abstract DeltaDiscoveryRequest request();

  /**
   * Returns the resources to include in the response.
   */
  public abstract Map<String, SnapshotResource<? extends Message>> resources();

  /**
   * Returns the removed resources to include in the response.
   */
  public abstract List<String> removedResources();

  /**
   * Returns the version of the resources as tracked by the cache for the given type. Envoy responds with this version
   * as an acknowledgement.
   */
  public abstract String version();
}
