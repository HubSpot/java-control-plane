package io.envoyproxy.controlplane.v3.server;

import com.google.common.util.concurrent.MoreExecutors;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Default implementation of {@link ExecutorGroup} which
 * always returns {@link MoreExecutors#directExecutor}.
 */
public class DefaultExecutorGroup implements ExecutorGroup {
  private static final ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

  /**
   * Returns the next {@link Executor} to use, which in this case is
   * always {@link MoreExecutors#directExecutor}.
   */
  @Override
  public Executor next() {
    return MoreExecutors.directExecutor();
  }

  @Override
  public ScheduledExecutorService nextScheduled() {
    return scheduledExecutor;
  }
}
