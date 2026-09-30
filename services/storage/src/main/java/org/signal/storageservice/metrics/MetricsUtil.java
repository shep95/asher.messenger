/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.storageservice.metrics;

import io.dropwizard.core.setup.Environment;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.resources.ResourceBuilder;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jetty.util.component.LifeCycle;
import org.signal.storageservice.StorageServiceConfiguration;
import org.signal.storageservice.StorageServiceVersion;
import org.signal.storageservice.util.HostSupplier;

public class MetricsUtil {

  public static final String PREFIX = "storage";

  private static volatile boolean registeredMetrics = false;

  /**
   * Returns a dot-separated ('.') name for the given class and name parts
   */
  public static String name(Class<?> clazz, String... parts) {
    return name(clazz.getSimpleName(), parts);
  }

  private static String name(String name, String... parts) {
    final StringBuilder sb = new StringBuilder(PREFIX);
    sb.append(".").append(name);
    for (String part : parts) {
      sb.append(".").append(part);
    }
    return sb.toString();
  }

  public static void configureRegistries(final StorageServiceConfiguration config, final Environment environment) {

    if (registeredMetrics) {
      throw new IllegalStateException("Metric registries configured more than once");
    }

    registeredMetrics = true;

    if (config.getOpenTelemetryConfiguration().enabled()) {
      final OtlpMeterRegistry otlpMeterRegistry = new OtlpMeterRegistry(config.getOpenTelemetryConfiguration(), Clock.SYSTEM);
      Metrics.addRegistry(otlpMeterRegistry);
      final DistributionStatisticConfig defaultDistributionStatisticConfig = DistributionStatisticConfig.builder().percentilesHistogram(true).build();
      otlpMeterRegistry.config().meterFilter(new MeterFilter() {
        @Override
        public DistributionStatisticConfig configure(final Meter.Id id, final DistributionStatisticConfig config) {
          return config.merge(defaultDistributionStatisticConfig);
        }
      });
    }
  }

  public static void registerSystemResourceMetrics(final Environment environment) {
    new ProcessorMetrics().bindTo(Metrics.globalRegistry);
    new FreeMemoryGauge().bindTo(Metrics.globalRegistry);
    new FileDescriptorMetrics().bindTo(Metrics.globalRegistry);

    new JvmMemoryMetrics().bindTo(Metrics.globalRegistry);
    new JvmThreadMetrics().bindTo(Metrics.globalRegistry);
  }

  public static void configureLogging(final StorageServiceConfiguration config, final Environment environment) {
    if (!config.getOpenTelemetryConfiguration().enabled()) {
      return;
    }

    final String endpoint =
      Optional.ofNullable(config.getOpenTelemetryConfiguration().logUrl())
        .orElse("http://localhost:4318/v1/logs");

    final ResourceBuilder resource = Resource.builder();
    config.getOpenTelemetryConfiguration().resourceAttributes().forEach(resource::put);

    final OpenTelemetrySdk openTelemetry =
      OpenTelemetrySdk.builder()
        .setLoggerProvider(
          SdkLoggerProvider.builder()
            .setResource(resource.build())
            .addLogRecordProcessor(
              BatchLogRecordProcessor.builder(
                OtlpHttpLogRecordExporter.builder()
                    .setEndpoint(endpoint)
                    .setHeaders(config.getOpenTelemetryConfiguration()::headers)
                  .build()).build())
            .build())
        .build();

    OpenTelemetryAppender.install(openTelemetry);

    environment.lifecycle().addEventListener(new LifeCycle.Listener() {
      @Override
      public void lifeCycleStopped(final LifeCycle event) {
        openTelemetry.close();
      }
    });
  }

}
