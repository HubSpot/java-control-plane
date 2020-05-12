package io.envoyproxy.controlplane.v3.cache;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Message;
import java.util.Map;
import java.util.stream.Collector;
import java.util.stream.StreamSupport;

@AutoValue
public abstract class SnapshotResources<T extends Message> {

  /**
   * Returns a new {@link SnapshotResources} instance.
   *
   * @param resources the resources in this collection
   * @param version   the global version associated with the resources in this collection
   * @param <T>       the type of resources in this collection
   */
  public static <T extends Message> SnapshotResources<T> create(
      Iterable<SnapshotResource<T>> resources,
      String version) {
    return new AutoValue_SnapshotResources<>(
        resourcesMap(resources),
        version
    );
  }

  private static <T extends Message> ImmutableMap<String, SnapshotResource<T>> resourcesMap(
      Iterable<SnapshotResource<T>> resources) {
    return StreamSupport.stream(resources.spliterator(), false)
        .collect(
            Collector.of(
                ImmutableMap.Builder<String, SnapshotResource<T>>::new,
                (b, e) -> b.put(Resources.getResourceName(e.resource()), e),
                (b1, b2) -> b1.putAll(b2.build()),
                ImmutableMap.Builder::build));
  }

  /**
   * Returns a map of the resources in this collection, where the key is the name of the resource.
   */
  public abstract Map<String, SnapshotResource<T>> resources();

  /**
   * Returns the global version associated with all resources in this collection.
   */
  public abstract String version();
}
