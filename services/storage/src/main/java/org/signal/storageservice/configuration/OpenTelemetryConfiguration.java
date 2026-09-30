/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.storageservice.configuration;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micrometer.registry.otlp.HistogramFlavor;
import io.micrometer.registry.otlp.OtlpConfig;
import java.util.Map;

import org.signal.storageservice.StorageServiceVersion;
import org.signal.storageservice.util.HostSupplier;

public record OpenTelemetryConfiguration(
  @JsonProperty boolean enabled,
  @JsonProperty String environment,
  @JsonProperty int maxBucketCount,
  @JsonProperty String logUrl,
  @JsonProperty Map<String, Integer> maxBucketsPerMeter,
  @JsonAnyGetter @JsonAnySetter Map<String, String> otlpConfig
) implements OtlpConfig {

  @Override
  public String get(String key) {
    return otlpConfig.get(key.split("\\.", 2)[1]);
  }

  @Override
  public Map<String, Integer> maxBucketsPerMeter() {
    if (maxBucketsPerMeter == null) {
      return Map.of();
    }
    return maxBucketsPerMeter;
  }

  @Override
  public HistogramFlavor histogramFlavor() {
    return HistogramFlavor.BASE2_EXPONENTIAL_BUCKET_HISTOGRAM;
  }

  @Override
  public Map<String, String> resourceAttributes() {
    return Map.of(
      "service.name", "storage",
      "service.instance.id", HostSupplier.getHost(),
      "service.version", StorageServiceVersion.getServiceVersion(),
      "deployment.environment.name", environment());
  }
}
