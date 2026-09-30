/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.gcp;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;

@Factory
@Requires(env = {Environment.GOOGLE_COMPUTE, Environment.CLI})
@Requires(property = "gcp.project-id")
public class StorageClientFactory {

  @Singleton
  Storage storageClient(@Value("${gcp.project-id}") final String projectId) {
    return StorageOptions.newBuilder().setProjectId(projectId).build().getService();
  }
}
