/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.analytics.sinch;

import com.google.cloud.storage.Storage;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;

@Singleton
@Requires(env = Environment.GOOGLE_COMPUTE)
public class GcsSinchPriceSheetSupplier implements SinchPriceSheetSupplier {

  private final GcpSinchPriceSheetConfiguration gcpSinchPriceSheetConfiguration;
  private final Storage storageClient;

  public GcsSinchPriceSheetSupplier(
      final GcpSinchPriceSheetConfiguration gcpSinchPriceSheetConfiguration, final Storage storageClient) {
    this.gcpSinchPriceSheetConfiguration = gcpSinchPriceSheetConfiguration;
    this.storageClient = storageClient;
  }

  @Override
  public String get() {
    final byte[] content = storageClient.readAllBytes(gcpSinchPriceSheetConfiguration.bucketName(),
        gcpSinchPriceSheetConfiguration.objectName());
    return new String(content, StandardCharsets.UTF_8);
  }
}
