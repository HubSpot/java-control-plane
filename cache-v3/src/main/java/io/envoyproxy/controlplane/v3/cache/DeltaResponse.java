package io.envoyproxy.controlplane.v3.cache;

import com.google.auto.value.AutoValue;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.Resource;
import java.util.Collection;
import java.util.Set;

/**
 * {@code Response} is a data class that contains the response for an assumed configuration type.
 */
@AutoValue
public abstract class DeltaResponse {

  public static DeltaResponse create(DeltaDiscoveryRequest request, Collection<Resource> resources, Set<String> removedResources, String version) {
    return new AutoValue_DeltaResponse(request, resources, removedResources, version);
  }

  /**
   * Returns the original request associated with the response.
   */
  public abstract DeltaDiscoveryRequest request();

  /**
   * Returns the resources to include in the response.
   */
  public abstract Collection<Resource> resources();

  /**
   * Returns the removed resources to include in the response.
   */
  public abstract Set<String> removedResources();

  /**
   * Returns the version of the resources as tracked by the cache for the given type. Envoy responds with this version
   * as an acknowledgement.
   */
  public abstract String version();
}
