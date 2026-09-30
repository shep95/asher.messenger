/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.analytics.sinch;

import io.micronaut.context.event.ApplicationEventPublisher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.registration.analytics.AttemptAnalysis;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.AttemptPendingAnalysisRepository;
import org.signal.registration.analytics.Money;
import reactor.core.scheduler.Scheduler;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SinchSmsAttemptAnalyzerTest {

  @ParameterizedTest
  @MethodSource
  void analyzeAttempt(final String regionCode, @Nullable final BigDecimal expectedPrice) {
    final String csvString = """
        AF,0.01
        US,0.00375
        """;
    final SinchSmsAttemptAnalyzer analyzer = new SinchSmsAttemptAnalyzer(() -> csvString, mock(AttemptPendingAnalysisRepository.class), mock(
        Scheduler.class), mock(ApplicationEventPublisher.class), mock(Clock.class));
    analyzer.refreshPricingInfo();
    final AttemptPendingAnalysis attemptPendingAnalysis = mock(AttemptPendingAnalysis.class);
    when(attemptPendingAnalysis.getRegion()).thenReturn(regionCode);
    final AttemptAnalysis attemptAnalysis = analyzer.analyzeAttempt(attemptPendingAnalysis);
    final Optional<Money> maybeExpectedPrice = Optional.ofNullable(expectedPrice).map(amount -> new Money(amount, Currency.getInstance("USD")));
    assertEquals(maybeExpectedPrice, attemptAnalysis.price());
  }

  static Stream<Arguments> analyzeAttempt() {
    return Stream.of(
        Arguments.argumentSet("Region code found", "AF", new BigDecimal("0.01")),
        Arguments.argumentSet("Region code not found", "XX", null)
    );
  }

}
