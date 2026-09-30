/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.sinch.classic;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.InvalidProtocolBufferException;
import com.sinch.sdk.core.exceptions.ApiException;
import com.sinch.sdk.domains.sms.api.v1.BatchesService;
import com.sinch.sdk.domains.sms.models.v1.batches.request.TextRequest;
import com.sinch.sdk.domains.sms.models.v1.batches.response.BatchResponse;
import com.sinch.sdk.domains.sms.models.v1.batches.response.TextResponse;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import jakarta.inject.Singleton;
import org.signal.registration.sender.ApiClientInstrumenter;
import org.signal.registration.sender.AttemptData;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderIdSelector;
import org.signal.registration.sender.SenderRejectedRequestException;
import org.signal.registration.sender.UnsupportedMessageTransportException;
import org.signal.registration.sender.VerificationCodeGenerator;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.sender.VerificationSmsBodyProvider;
import org.signal.registration.sender.sinch.SinchClassicSessionData;
import org.signal.registration.sender.sinch.SinchExceptions;
import org.signal.registration.sender.sinch.SinchSenderConfiguration;
import org.signal.registration.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Sends SMS verification codes via the Sinch API
///
/// Sinch SMS [API reference](https://developers.sinch.com/docs/sms/api-reference/)
@Singleton
public class SinchSmsSender implements VerificationCodeSender {

  private final SinchSmsConfiguration sinchSmsConfiguration;
  private final VerificationCodeGenerator verificationCodeGenerator;
  private final VerificationSmsBodyProvider verificationSmsBodyProvider;
  private final ApiClientInstrumenter apiClientInstrumenter;
  private final BatchesService smsClient;
  private final SenderIdSelector senderIdSelector;

  public static final String SENDER_NAME = "sinch-sms";

  private static final Logger logger = LoggerFactory.getLogger(SinchSmsSender.class);

  public SinchSmsSender(final SinchSmsConfiguration sinchSmsConfiguration,
      final VerificationCodeGenerator verificationCodeGenerator,
      final VerificationSmsBodyProvider verificationSmsBodyProvider,
      final ApiClientInstrumenter apiClientInstrumenter,
      final BatchesService smsClient,
      final SinchSenderConfiguration sinchSenderConfiguration) {
    this.sinchSmsConfiguration = sinchSmsConfiguration;
    this.verificationCodeGenerator = verificationCodeGenerator;
    this.verificationSmsBodyProvider = verificationSmsBodyProvider;
    this.apiClientInstrumenter = apiClientInstrumenter;
    this.smsClient = smsClient;
    this.senderIdSelector = SenderIdSelector.fromConfiguration(sinchSenderConfiguration);
  }

  @Override
  public String getName() {
    return SENDER_NAME;
  }

  @Override
  public Duration getAttemptTtl() {
    return sinchSmsConfiguration.sessionTtl();
  }

  @Override
  public boolean supportsTransport(final MessageTransport transport) {
    return transport == MessageTransport.SMS;
  }

  @Override
  public boolean supportsLanguage(final MessageTransport messageTransport, final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges) {
    return verificationSmsBodyProvider.supportsLanguage(languageRanges);
  }

  @Override
  public AttemptData sendVerificationCode(final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber, final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) throws SenderRejectedRequestException {

    if (messageTransport != MessageTransport.SMS) {
      throw new UnsupportedMessageTransportException();
    }

    final String e164 = PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164);
    final String verificationCode = verificationCodeGenerator.generateVerificationCode();
    final String body = verificationSmsBodyProvider.getVerificationBody(phoneNumber, clientType, verificationCode,
        languageRanges);
    final String from = senderIdSelector.getSenderId(phoneNumber);

    final Timer.Sample sample = Timer.start();
    try {
      final BatchResponse response = smsClient.send(
          TextRequest.builder()
              .setFrom(from)
              .setTo(Collections.singletonList(e164))
              .setBody(body)
              .build()
      );

      if (response instanceof TextResponse textResponse) {
        apiClientInstrumenter.recordApiCallMetrics(
            getName(),
            "sms.create",
            true,
            null,
            sample);
        return new AttemptData(Optional.of(textResponse.getId()),
            SinchClassicSessionData.newBuilder().setVerificationCode(verificationCode).build()
                .toByteArray());
      } else {
        logger.warn("Received invalid response type from Sinch SMS API: {}, this should not happen unless Sinch is having an outage/messed up a deploy", response.getClass());
        throw new IllegalArgumentException("Received invalid response type from Sinch SMS API: " + response.getClass());
      }
    } catch (final ApiException e) {
      logger.debug("Failed to send SMS", e);
      apiClientInstrumenter.recordApiCallMetrics(
          getName(),
          "sms.create",
          false,
          String.valueOf(e.getCode()),
          sample);
      throw SinchExceptions.toSenderRejectedException(e)
          .orElseThrow(() -> new UncheckedIOException(new IOException(e)));
    }

  }

  @Override
  public boolean checkVerificationCode(final String verificationCode, final byte[] senderData)
      throws SenderRejectedRequestException {
    try {
      final String storedVerificationCode = SinchClassicSessionData.parseFrom(senderData).getVerificationCode();
      return Strings.equalsConstantTime(storedVerificationCode, verificationCode);
    } catch (final InvalidProtocolBufferException e) {
      logger.error("Failed to parse stored session data", e);
      throw new UncheckedIOException(e);
    }
  }
}
