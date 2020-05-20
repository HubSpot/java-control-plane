package io.envoyproxy.controlplane.v3.server;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The {@link ExecutorGroup} is responsible for providing the {@link ScheduledExecutorService}'s to use
 * via its {@link #next()} method.
 */
public interface ExecutorGroup {
  /**
   * Returns the next {@link ExecutorGroup} to use.
   */
  Executor next();

  /**
   * Returns the next {@link ScheduledExecutorService} to use.
   */
  ScheduledExecutorService nextScheduled();
}
