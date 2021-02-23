package io.envoyproxy.controlplane.cache;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collector;
import java.util.stream.StreamSupport;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.google.protobuf.Message;

@AutoValue
public abstract class SnapshotResources<T extends Message> {

  /**
   * Returns a new {@link SnapshotResources} instance.
   *
   * @param resources the resources in this collection
   * @param version the version associated with the resources in this collection
   * @param <T> the type of resources in this collection
   */
  public static <T extends Message> SnapshotResources<T> create(
      Iterable<SnapshotResource<T>> resources,
      String version) {
    return new AutoValue_SnapshotResources<>(
        resourcesMap(resources),
        (r) -> version
    );
  }

  public static <T extends Message> SnapshotResources<T> create(
      Iterable<T> resources,
      Iterable<String> versions) {
    ImmutableMap<String, SnapshotResource<T>> map = resourcesMap(resources, versions);
    return new AutoValue_SnapshotResources<>(
        map,
        // todo: well this is super wrong, but leave it here for now
        (r) -> map.values().asList().get(0).version()
    );
  }

  /**
   * Returns a new {@link SnapshotResources} instance with versions by resource name.
   *
   * @param resources the resources in this collection
   * @param versionResolver version resolver for the resources in this collection
   * @param <T> the type of resources in this collection
   */
  public static <T extends Message> SnapshotResources<T> create(
      Iterable<SnapshotResource<T>> resources,
      ResourceVersionResolver versionResolver) {
    return new AutoValue_SnapshotResources<>(
        resourcesMap(resources),
        versionResolver);
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

  public static <T> Iterable<T> getIterableFromIterator(Iterator<T> iterator) {
    return () -> iterator;
  }

  private static <T extends Message> ImmutableMap<String, SnapshotResource<T>> resourcesMap(
      Iterable<T> resources, Iterable<String> versions) {
    Iterator<SnapshotResource<T>> iterator = Streams.zip(
        StreamSupport.stream(resources.spliterator(), false),
        StreamSupport.stream(versions.spliterator(), false),
        (resource, version) -> {
          return SnapshotResource.create(resource, version);
        }).iterator();
    return resourcesMap(getIterableFromIterator(iterator));

  }

  /**
   * Returns a map of the resources in this collection, where the key is the name of the resource.
   */
  public abstract Map<String, SnapshotResource<T>> resources();

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
