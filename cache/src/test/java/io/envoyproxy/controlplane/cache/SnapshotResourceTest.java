package io.envoyproxy.controlplane.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.envoyproxy.envoy.api.v2.Cluster;
import org.junit.Test;

public class SnapshotResourceTest {

  @Test
  public void itCreatesEquivalentObjectsWithoutVersionNumbers() {

    SnapshotResource<Cluster> cluster0 = SnapshotResource.create(
        TestResources.createCluster("cluster0"));

    SnapshotResource<Cluster> cluster1 = SnapshotResource.create(
        TestResources.createCluster("cluster0"));

    assertThat(cluster0).isEqualTo(cluster1);
  }

  @Test
  public void itCreatesEquivalentObjectsWithVersionNumbers() {

    SnapshotResource<Cluster> cluster0 = SnapshotResource.create(
        TestResources.createCluster("cluster0"), "v1");

    SnapshotResource<Cluster> cluster1 = SnapshotResource.create(
        TestResources.createCluster("cluster0"), "v1");

    assertThat(cluster0).isEqualTo(cluster1);
  }
}
