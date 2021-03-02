package io.envoyproxy.controlplane.cache;

import com.google.auto.value.AutoValue;
import com.google.common.hash.Hashing;
import com.google.protobuf.Message;
import java.nio.charset.StandardCharsets;

@AutoValue
public abstract class SnapshotResource<T extends Message> {

  /**
   * Returns a new {@link SnapshotResource} instance.
   *
   * @param resource the resource
   * @param version  the version associated with the resource
   * @param <T>      the type of resource
   */
  public static <T extends Message> SnapshotResource<T> create(T resource, String version) {
    return new AutoValue_SnapshotResource<>(
        resource,
        version
    );
  }

  public static <T extends Message> SnapshotResource<T> create(T resource) {
    return new AutoValue_SnapshotResource<>(
        resource,
        // todo: is this a stable hash?
        Hashing.sha256()
            .hashString(resource.toString(), StandardCharsets.UTF_8)
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
