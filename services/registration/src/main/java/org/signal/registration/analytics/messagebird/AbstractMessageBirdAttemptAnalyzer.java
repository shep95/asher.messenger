/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.messagebird;

import com.google.common.annotations.VisibleForTesting;
import com.messagebird.exceptions.MessageBirdException;
import com.messagebird.exceptions.NotFoundException;
import com.messagebird.objects.MessageResponse;
import io.micronaut.context.event.ApplicationEventPublisher;
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
import org.signal.registration.analytics.PriceEstimator;
import reactor.core.scheduler.Scheduler;

abstract class AbstractMessageBirdAttemptAnalyzer extends AbstractAttemptAnalyzer {

  private final PriceEstimator priceEstimator;

  AbstractMessageBirdAttemptAnalyzer(final AttemptPendingAnalysisRepository repository,
      final Scheduler scheduler,
      final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher,
      final Clock clock, final PriceEstimator priceEstimator) {

    super(repository, scheduler, attemptAnalyzedEventPublisher, clock);

    this.priceEstimator = priceEstimator;
  }

  protected abstract MessageResponse.Recipients getRecipients(final AttemptPendingAnalysis attemptPendingAnalysis) throws MessageBirdException;

  @Override
  protected AttemptAnalysis analyzeAttempt(final AttemptPendingAnalysis attemptPendingAnalysis) {
    try {
      return extractAttemptAnalysis(getRecipients(attemptPendingAnalysis), attemptPendingAnalysis, priceEstimator);
    } catch (final NotFoundException ignored) {
      return AttemptAnalysis.EMPTY;
    } catch (final MessageBirdException e) {
      // TODO Log
      throw new RuntimeException(e);
    }
  }

  @VisibleForTesting
  static AttemptAnalysis extractAttemptAnalysis(final MessageResponse.Recipients recipients, final AttemptPendingAnalysis attemptPendingAnalysis, final PriceEstimator priceEstimator) {
    final Optional<Money> maybePrice = recipients.getItems().stream()
        .map(MessageResponse.Items::getPrice)
        .filter(price -> price != null && StringUtils.isNotBlank(price.getCurrency()))
        .map(price -> new Money(price.getAmountDecimal(), Currency.getInstance(price.getCurrency().toUpperCase(Locale.ROOT))))
        .reduce(Money::add);

    final Optional<String> maybeMcc =
        recipients.getItems().stream().map(MessageResponse.Items::getMcc).filter(StringUtils::isNotBlank).findFirst();

    final Optional<String> maybeMnc =
        recipients.getItems().stream().map(MessageResponse.Items::getMnc).filter(StringUtils::isNotBlank).findFirst();

    return new AttemptAnalysis(maybePrice,
        priceEstimator.estimatePrice(attemptPendingAnalysis, maybeMcc.orElse(null), maybeMnc.orElse(null)),
        maybeMcc,
        maybeMnc);
  }
}
