package io.envoyproxy.controlplane.cache;

import com.google.auto.value.AutoValue;
import com.google.common.hash.Hashing;
import com.google.protobuf.Message;

@AutoValue
public abstract class VersionedResource<T extends Message> {

  /**
   * Returns a new {@link VersionedResource} instance.
   *
   * @param resource the resource
   * @param version  the version associated with the resource
   * @param <T>      the type of resource
   */
  public static <T extends Message> VersionedResource<T> create(T resource, String version) {
    return new AutoValue_VersionedResource<>(
        resource,
        version
    );
  }

  /**
   * Returns a new {@link VersionedResource} instance.
   *
   * @param resource the resource
   * @param <T>      the type of resource
   */
  public static <T extends Message> VersionedResource<T> create(T resource) {
    return new AutoValue_VersionedResource<>(
        resource,
        Hashing.sha256()
            .hashBytes(resource.toByteArray())
            .toString()
    );
  }

  /**
   * Returns the resource.
   */
  public abstract T resource();

  /**
   * Returns the version associated with the resource.
   */
  public abstract String version();

}
