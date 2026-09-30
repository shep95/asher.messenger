/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.analytics.sinch;

import com.google.common.annotations.VisibleForTesting;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.signal.registration.analytics.AbstractAttemptAnalyzer;
import org.signal.registration.analytics.AttemptAnalysis;
import org.signal.registration.analytics.AttemptAnalyzedEvent;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.AttemptPendingAnalysisRepository;
import org.signal.registration.analytics.Money;
import org.signal.registration.sender.sinch.classic.SinchSmsSender;
import reactor.core.scheduler.Scheduler;


@Singleton
@Requires(notEnv = Environment.TEST)
public class SinchSmsAttemptAnalyzer extends AbstractAttemptAnalyzer {

  private final SinchPriceSheetSupplier priceSheetSupplier;
  private Map<String, Money> regionToPrice;

  public SinchSmsAttemptAnalyzer(final SinchPriceSheetSupplier priceSheetSupplier,
      final AttemptPendingAnalysisRepository repository,
      final Scheduler scheduler,
      final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher,
      final Clock clock) {
    super(repository, scheduler, attemptAnalyzedEventPublisher, clock);
    this.priceSheetSupplier = priceSheetSupplier;
  }

  @Override
  protected String getSenderName() {
    return SinchSmsSender.SENDER_NAME;
  }

  @Override
  @Scheduled(fixedDelay = "${analytics.sinch.sms.analysis-interval:4h}")
  protected void analyzeAttempts() {
    refreshPricingInfo();
    super.analyzeAttempts();
  }

  @VisibleForTesting
  void refreshPricingInfo() {
    try {
      final String csvString = priceSheetSupplier.get();
      // The format is "<region>,<price in USD>"
      regionToPrice = CSVFormat.DEFAULT.builder()
          .get()
          .parse(new StringReader(csvString))
          .stream()
          .collect(Collectors.toMap(record -> record.get(0).trim(), record -> {
            final String priceInUSD = record.get(1).trim();
            try {
              return new Money(new BigDecimal(priceInUSD), Currency.getInstance("USD"));
            } catch (final NumberFormatException e) {
              throw new UncheckedIOException(
                  "Failed to parse price %s for region %s in pricing sheet".formatted(priceInUSD, record.get(0)),
                  new IOException(e));
            }
          }));
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to parse sinch pricing CSV", e);
    }

  }

  @Override
  protected AttemptAnalysis analyzeAttempt(final AttemptPendingAnalysis attemptPendingAnalysis) {
    final String regionCode = attemptPendingAnalysis.getRegion().toUpperCase(Locale.ROOT);
    final Optional<Money> price = Optional.ofNullable(regionToPrice.get(regionCode));
    return new AttemptAnalysis(price,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }
}
