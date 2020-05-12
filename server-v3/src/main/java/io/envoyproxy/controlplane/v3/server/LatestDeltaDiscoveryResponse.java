package io.envoyproxy.controlplane.v3.server;

import com.google.auto.value.AutoValue;
import java.util.Map;
import java.util.Set;

/**
 * Class introduces optimization which store only required data during next request.
 */
@AutoValue
public abstract class LatestDeltaDiscoveryResponse {
  static LatestDeltaDiscoveryResponse create(String nonce, Map<String, String> resourceVersions, Set<String> removedResources) {
    return new AutoValue_LatestDeltaDiscoveryResponse(nonce, resourceVersions, removedResources);
  }

  abstract String nonce();

  abstract Map<String, String> resourceVersions();

  abstract Set<String> removedResources();
}
