/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.twilio;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.signal.registration.analytics.AbstractAttemptAnalyzer;
import org.signal.registration.analytics.AttemptAnalysis;
import org.signal.registration.analytics.AttemptAnalyzedEvent;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.AttemptPendingAnalysisRepository;
import org.signal.registration.sender.twilio.verify.TwilioVerifySender;
import reactor.core.scheduler.Schedulers;

/**
 * Analyzes verification attempts from {@link TwilioVerifySender}.
 */
@Singleton
@Requires(notEnv = Environment.TEST)
class TwilioVerifyAttemptAnalyzer extends AbstractAttemptAnalyzer {

  private final TwilioVerifyPriceEstimator twilioVerifyPriceEstimator;

  TwilioVerifyAttemptAnalyzer(final AttemptPendingAnalysisRepository repository,
      final TwilioVerifyPriceEstimator twilioVerifyPriceEstimator,
      final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher,
      final Clock clock) {

    super(repository, Schedulers.immediate(), attemptAnalyzedEventPublisher, clock);

    this.twilioVerifyPriceEstimator = twilioVerifyPriceEstimator;
  }

  @Override
  @Scheduled(fixedDelay = "${analytics.twilio.verify.analysis-interval:5m}",
      initialDelay = "${analytics.twilio.verify.analysis-initial-delay:5m}")
  protected void analyzeAttempts() {
    super.analyzeAttempts();
  }

  @Override
  protected String getSenderName() {
    return TwilioVerifySender.SENDER_NAME;
  }

  @Override
  protected Duration getPricingDeadline() {
    // Always use estimated prices for Twilio Verify
    return Duration.ZERO;
  }

  @Override
  protected AttemptAnalysis analyzeAttempt(final AttemptPendingAnalysis attemptPendingAnalysis) {
    return new AttemptAnalysis(Optional.empty(),
        twilioVerifyPriceEstimator.estimatePrice(attemptPendingAnalysis, null, null),
        Optional.empty(),
        Optional.empty());
  }
}
