package io.envoyproxy.controlplane.cache;

import com.google.auto.value.AutoValue;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.protobuf.Message;
import java.util.List;
import java.util.Map;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@AutoValue
public abstract class SnapshotResources<T extends Message> {

  /**
   * Returns a new {@link SnapshotResources} instance.
   *
   * @param resources the resources in this collection
   * @param version   the version associated with the resources in this collection
   * @param <T>       the type of resources in this collection
   */
  public static <T extends Message> SnapshotResources<T> create(
      Iterable<?> resources,
      String version) {
    return new AutoValue_SnapshotResources<>(
        resourcesMap(resources),
        (r) -> version
    );
  }


  /**
   * Returns a new {@link SnapshotResources} instance with versions by resource name.
   *
   * @param resources       the resources in this collection
   * @param versionResolver version resolver for the resources in this collection
   * @param <T>             the type of resources in this collection
   */
  public static <T extends Message> SnapshotResources<T> create(
      Iterable<SnapshotResource<T>> resources,
      ResourceVersionResolver versionResolver) {
    return new AutoValue_SnapshotResources<>(
        resourcesMap(resources),
        versionResolver);
  }

  private static <T extends Message> ImmutableMap<String, SnapshotResource<T>> resourcesMap(
      Iterable<?> resources) {
    List<?> resourcesList = StreamSupport.stream(resources.spliterator(), false)
        .collect(Collectors.toList());
    if (resourcesList.stream().allMatch(Predicates.instanceOf(SnapshotResource.class)::apply)) {
      ImmutableMap<String, SnapshotResource<T>> result = StreamSupport
          .stream(resourcesList.spliterator(), false)
          .collect(
              Collector.of(
                  Builder<String, SnapshotResource<T>>::new,
                  (b, e) -> {
                    SnapshotResource<T> eCast = (SnapshotResource<T>) e;
                    b.put(Resources.getResourceName(eCast.resource()), eCast);
                  },
                  (b1, b2) -> b1.putAll(b2.build()),
                  Builder::build));
      return result;
    } else {
      return StreamSupport.stream(resources.spliterator(), false)
          .collect(
              Collector.of(
                  Builder<String, SnapshotResource<T>>::new,
                  (b, e) -> {
                    T eCast = (T) e;
                    b.put(Resources.getResourceName(eCast), SnapshotResource.create(eCast));
                  },
                  (b1, b2) -> b1.putAll(b2.build()),
                  Builder::build));
    }
  }

  /**
   * Returns a map of the resources in this collection, where the key is the name of the resource.
   */
  public abstract Map<String, SnapshotResource<T>> resources();

  /**
   * Returns a map of the underlying resources in this collection, where the key is the name of the
   * resource.
   */
  public Map<String, T> getUnderlyingResources() {
    return resources().values().stream().collect(
        Collector.of(
            Builder<String, T>::new,
            (b, e) -> {
              b.put(Resources.getResourceName(e.resource()), e.resource());
            },
            (b1, b2) -> b1.putAll(b2.build()),
            Builder::build));
  }

  /**
   * Returns the version associated with this all resources in this collection.
   */
  public String version() {
    return resourceVersionResolver().version();
  }

  /**
   * Returns the version associated with the requested resources in this collection.
   *
   * @param resourceNames list of list of requested resources.
   */
  public String version(List<String> resourceNames) {
    return resourceVersionResolver().version(resourceNames);
  }

  /**
   * Returns the version resolver associated with this resources in this collection.
   */
  public abstract ResourceVersionResolver resourceVersionResolver();

}
