/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.infobip;

import com.infobip.ApiException;
import com.infobip.api.SmsApi;
import com.infobip.model.SmsLog;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.arrow.util.VisibleForTesting;
import org.signal.registration.analytics.AttemptAnalysis;
import org.signal.registration.analytics.AttemptAnalyzedEvent;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.AttemptPendingAnalysisRepository;
import org.signal.registration.analytics.Money;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.sender.infobip.classic.InfobipSmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Singleton
@Requires(notEnv = Environment.TEST)
class InfobipSmsAttemptAnalyzer {
  private final AttemptPendingAnalysisRepository repository;
  private final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher;
  private final Clock clock;

  private final SmsApi infobipSmsApiClient;
  private final InfobipSmsPriceEstimator infobipSmsPriceEstimator;
  private final MeterRegistry meterRegistry;
  private final int pageSize;

  private static final Logger logger = LoggerFactory.getLogger(InfobipSmsAttemptAnalyzer.class);
  private static final int MIN_MCC_MNC_LENGTH = 5;
  private static final int MAX_RETRIES = 10;
  private static final Duration MIN_BACKOFF = Duration.ofMillis(500);
  private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
  private static final Duration PRICING_DEADLINE = Duration.ofHours(36);
  private static final int HTTP_TOO_MANY_REQUESTS_CODE = 429;

  protected InfobipSmsAttemptAnalyzer(
      final AttemptPendingAnalysisRepository repository,
      final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher,
      final Clock clock,
      final SmsApi infobipSmsApiClient,
      final InfobipSmsPriceEstimator infobipSmsPriceEstimator,
      final MeterRegistry meterRegistry,
      @Value("${analytics.infobip.sms.page-size}") final int pageSize) {

    this.repository = repository;
    this.attemptAnalyzedEventPublisher = attemptAnalyzedEventPublisher;
    this.clock = clock;
    this.infobipSmsApiClient = infobipSmsApiClient;
    this.infobipSmsPriceEstimator = infobipSmsPriceEstimator;
    this.meterRegistry = meterRegistry;
    this.pageSize = pageSize;
  }

  @Scheduled(fixedDelay = "${analytics.infobip.sms.analysis-interval:4h}")
  protected void analyzeAttempts() {
    // Infobip's message logs API (https://www.infobip.com/docs/api/channels/sms/sms-messaging/logs-and-status-reports/get-outbound-sms-message-logs)
    // has a ratelimit that prevents us from querying one by one for each attempt pending analysis.
    // Instead, we fetch fewer, larger pages and reconcile them against what we have stored locally.
    Flux.fromStream(repository.getBySender(InfobipSmsSender.SENDER_NAME))
        .buffer(pageSize)
        .flatMap(attemptsPendingAnalysis -> fetchSmsLogsWithBackoff(attemptsPendingAnalysis.stream().map(AttemptPendingAnalysis::getRemoteId).toList())
            .flatMapMany(smsLogsByRemoteId -> Flux.fromIterable(attemptsPendingAnalysis)
                .map(attemptPendingAnalysis -> {
                  final AttemptAnalysis attemptAnalysis;

                  if (smsLogsByRemoteId.containsKey(attemptPendingAnalysis.getRemoteId())) {
                    final SmsLog smsLog = smsLogsByRemoteId.get(attemptPendingAnalysis.getRemoteId());
                    final MccMnc mccMnc = MccMnc.fromString(smsLog.getMccMnc());

                    attemptAnalysis = new AttemptAnalysis(
                        extractPrice(smsLog),
                        infobipSmsPriceEstimator.estimatePrice(attemptPendingAnalysis, mccMnc.mcc(), mccMnc.mnc()),
                        Optional.ofNullable(mccMnc.mcc),
                        Optional.ofNullable(mccMnc.mnc));
                  } else {
                    attemptAnalysis = new AttemptAnalysis(
                        Optional.empty(),
                        infobipSmsPriceEstimator.estimatePrice(attemptPendingAnalysis, null, null),
                        Optional.empty(),
                        Optional.empty());
                  }

                  return new AttemptAnalyzedEvent(attemptPendingAnalysis, attemptAnalysis);
                })), 1) // Request at most one page of results from Infobip at a time
        .filter(attemptAnalyzedEvent -> {
          final Instant attemptTimestamp = Instant.ofEpochMilli(attemptAnalyzedEvent.attemptPendingAnalysis().getTimestampEpochMillis());
          final boolean pricingDeadlineExpired = clock.instant().isAfter(attemptTimestamp.plus(PRICING_DEADLINE));

          return attemptAnalyzedEvent.attemptAnalysis().price().isPresent() || pricingDeadlineExpired;
        })
        .doOnNext(attemptAnalyzedEvent -> {
          meterRegistry.counter(MetricsUtil.name(getClass(), "attemptAnalyzed"),
              "hasPrice", String.valueOf(attemptAnalyzedEvent.attemptAnalysis().price().isPresent())).increment();
          repository.remove(attemptAnalyzedEvent.attemptPendingAnalysis());
          attemptAnalyzedEventPublisher.publishEvent(attemptAnalyzedEvent);
        })
        .then()
        .block();
  }

  private Mono<Map<String, SmsLog>> fetchSmsLogsWithBackoff(final List<String> remoteIds) {
    return Mono.fromCallable(() -> getSmsLogs(remoteIds))
        .retryWhen(Retry.backoff(MAX_RETRIES, MIN_BACKOFF)
            .filter(throwable -> throwable instanceof ApiException apiException &&
                apiException.responseStatusCode() == HTTP_TOO_MANY_REQUESTS_CODE)
            .maxBackoff(MAX_BACKOFF))
        .map(smsLogs -> smsLogs.stream().collect(Collectors.toMap(SmsLog::getMessageId, smsLog -> smsLog)));
  }

  private List<SmsLog> getSmsLogs(final List<String> remoteIds) throws ApiException {
    try {
      final List<SmsLog> smsLogs =
          infobipSmsApiClient.getOutboundSmsMessageLogs().messageId(remoteIds).execute().getResults();
      meterRegistry.counter(MetricsUtil.name(getClass(), "getSmsLogs")).increment(smsLogs.size());
      return smsLogs;
    } catch (final ApiException e) {
      Tags tags = Tags.of("statusCode", String.valueOf(e.responseStatusCode()));
      if (e.details() != null) {
        tags = tags.and("error", e.details().getDescription());
      }
      meterRegistry.counter(MetricsUtil.name(getClass(), "infobipException"), tags).increment();

      throw e;
    }
  }

  private Optional<Money> extractPrice(final SmsLog smsLog) {
    final boolean hasPriceData = smsLog.getPrice() != null && smsLog.getPrice().getPricePerMessage() != null
        && smsLog.getPrice().getCurrency() != null;
    return hasPriceData
        ? Optional.of(new Money(BigDecimal.valueOf(smsLog.getPrice().getPricePerMessage()), Currency.getInstance(smsLog.getPrice().getCurrency())))
        : Optional.empty();
  }

  @VisibleForTesting
  record MccMnc(String mcc, String mnc) {
    private static final MccMnc EMPTY = new MccMnc(null, null);

    @VisibleForTesting
    static MccMnc fromString(@Nullable final String mccMnc) {
      if (mccMnc == null) {
        return EMPTY;
      }

      if (mccMnc.length() < MIN_MCC_MNC_LENGTH) {
        logger.debug("Invalid mccMnc string {}", mccMnc);
        return EMPTY;
      }

      // Mobile country code is always 3 digits: https://en.wikipedia.org/wiki/Mobile_country_code
      return new MccMnc(mccMnc.substring(0, 3), mccMnc.substring(3));
    }
  }
}
