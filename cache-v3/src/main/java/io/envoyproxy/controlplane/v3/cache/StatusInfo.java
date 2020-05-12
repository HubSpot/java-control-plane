package io.envoyproxy.controlplane.v3.cache;

import io.envoyproxy.envoy.config.core.v3.Node;

/**
 * {@code StatusInfo} tracks the state for remote envoy nodes.
 */
public interface StatusInfo<T> {

  /**
   * Returns the timestamp of the last discovery watch request.
   */
  long lastWatchRequestTime();

  /**
   * Returns the timestamp of the last discovery delta watch request.
   */
  long lastDeltaWatchRequestTime();

  /**
   * Returns the node grouping represented by this status, generated via {@link NodeGroup#hash(Node)}.
   */
  T nodeGroup();

  /**
   * Returns the number of open watches.
   */
  int numWatches();

  /**
   * Returns the number of open delta watches.
   */
  int numDeltaWatches();
}
