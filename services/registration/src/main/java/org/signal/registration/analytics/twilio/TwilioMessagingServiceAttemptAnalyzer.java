/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.twilio;

import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.Message;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.analytics.AbstractAttemptAnalyzer;
import org.signal.registration.analytics.AttemptAnalysis;
import org.signal.registration.analytics.AttemptAnalyzedEvent;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.AttemptPendingAnalysisRepository;
import org.signal.registration.analytics.Money;
import org.signal.registration.sender.twilio.classic.TwilioMessagingServiceSmsSender;
import reactor.core.scheduler.Schedulers;

/**
 * Analyzes verification attempts from {@link TwilioMessagingServiceSmsSender}.
 */
@Singleton
@Requires(notEnv = Environment.TEST)
class TwilioMessagingServiceAttemptAnalyzer extends AbstractAttemptAnalyzer {

  private final TwilioRestClient twilioRestClient;
  private final TwilioMessagingPriceEstimator twilioMessagingPriceEstimator;
  private final CarrierFeeAdjuster carrierFeeAdjuster;

  protected TwilioMessagingServiceAttemptAnalyzer(final AttemptPendingAnalysisRepository repository,
      final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher,
      final Clock clock,
      final TwilioRestClient twilioRestClient,
      final TwilioMessagingPriceEstimator twilioMessagingPriceEstimator,
      @Named("sms") final CarrierFeeAdjuster carrierFeeAdjuster) {

    super(repository, Schedulers.immediate(), attemptAnalyzedEventPublisher, clock);

    this.twilioRestClient = twilioRestClient;
    this.twilioMessagingPriceEstimator = twilioMessagingPriceEstimator;
    this.carrierFeeAdjuster = carrierFeeAdjuster;
  }

  @Override
  protected String getSenderName() {
    return TwilioMessagingServiceSmsSender.SENDER_NAME;
  }

  @Override
  @Scheduled(fixedDelay = "${analytics.twilio.sms.analysis-interval:4h}")
  protected void analyzeAttempts() {
    super.analyzeAttempts();
  }

  @Override
  protected AttemptAnalysis analyzeAttempt(final AttemptPendingAnalysis attemptPendingAnalysis) {
    final Message message = Message.fetcher(attemptPendingAnalysis.getRemoteId()).fetch(twilioRestClient);

    final Optional<Money> maybePrice = StringUtils.isNotBlank(message.getPrice()) && message.getPriceUnit() != null
        ? Optional.of(new Money(new BigDecimal(message.getPrice()).negate(),
        Currency.getInstance(message.getPriceUnit().getCurrencyCode().toUpperCase(Locale.ROOT))))
        : Optional.empty();
    return new AttemptAnalysis(maybePrice.map(price -> carrierFeeAdjuster.addCarrierFeeIfApplicable(price, attemptPendingAnalysis.getRegion())),
        twilioMessagingPriceEstimator
            .estimatePrice(attemptPendingAnalysis, null, null),
        Optional.empty(),
        Optional.empty());
  }
}
