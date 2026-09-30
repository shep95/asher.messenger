/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration;

import io.grpc.ServerBuilder;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.concurrent.Executor;

@Singleton
class GrpcServerExecutorCustomizer implements BeanCreatedEventListener<ServerBuilder<?>> {

  private final Executor grpcServerExecutor;

  GrpcServerExecutorCustomizer(@Named(TaskExecutors.VIRTUAL) final Executor grpcServerExecutor) {
    this.grpcServerExecutor = grpcServerExecutor;
  }

  @Override
  public ServerBuilder<?> onCreated(@NonNull final BeanCreatedEvent<ServerBuilder<?>> event) {
    // TODO We can do this at the configuration level instead if/when
    //  https://github.com/micronaut-projects/micronaut-grpc/issues/1097 gets resolved
    return event.getBean().executor(grpcServerExecutor);
  }
}
