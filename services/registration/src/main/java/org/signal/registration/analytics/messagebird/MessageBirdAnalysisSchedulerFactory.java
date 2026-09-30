/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.messagebird;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Factory
class MessageBirdAnalysisSchedulerFactory {

  static final String SCHEDULER_NAME = "messagebird-analysis";

  @Singleton
  @Named(SCHEDULER_NAME)
  Scheduler messagebirdAnalysisScheduler(
      @Property(name = "analytics.messagebird.max-concurrency", defaultValue = "32") final int maxConcurrency,
      @Property(name = "analytics.messagebird.max-queued-tasks", defaultValue = "65536") final int maxQueuedTasks) {

    return Schedulers.newBoundedElastic(maxConcurrency, maxQueuedTasks, SCHEDULER_NAME);
  }
}
