/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sender.sinch.classic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.sinch.sdk.core.exceptions.ApiException;
import com.sinch.sdk.domains.sms.api.v1.BatchesService;
import com.sinch.sdk.domains.sms.models.v1.batches.response.TextResponse;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.signal.registration.sender.ApiClientInstrumenter;
import org.signal.registration.sender.AttemptData;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderIdSelector;
import org.signal.registration.sender.SenderRateLimitedRequestException;
import org.signal.registration.sender.SenderRejectedRequestException;
import org.signal.registration.sender.VerificationCodeGenerator;
import org.signal.registration.sender.VerificationSmsBodyProvider;
import org.signal.registration.sender.sinch.SinchSenderConfiguration;

@ExtendWith(MockitoExtension.class)
class SinchSmsSenderTest {

  @Mock
  private VerificationCodeGenerator codeGenerator;

  @Mock
  private BatchesService batchesService;

  private static final SinchSenderConfiguration SINCH_SENDER_CONFIGURATION = new SinchSenderConfiguration(
      "SIGNAL",
      Collections.emptyMap()
  );

  private SinchSmsSender sinchSmsSender;

  @BeforeEach
  void setUp() {
    final SinchSmsConfiguration config = new SinchSmsConfiguration(Duration.ofSeconds(1));
    final VerificationSmsBodyProvider bodyProvider = mock(VerificationSmsBodyProvider.class);

    when(bodyProvider.getVerificationBody(any(), any(), anyString(), anyList())).thenReturn("body");
    when(codeGenerator.generateVerificationCode()).thenReturn("1234");

    sinchSmsSender = new SinchSmsSender(config, codeGenerator, bodyProvider, mock(ApiClientInstrumenter.class),
        batchesService, SINCH_SENDER_CONFIGURATION);
  }

  @Test
  void sendAndVerifyCode() throws Exception {
    final TextResponse response = mock(TextResponse.class);
    when(response.getId()).thenReturn("id");
    when(batchesService.send(any())).thenReturn(response);

    final AttemptData attemptData = sinchSmsSender.sendVerificationCode(MessageTransport.SMS,
        PhoneNumberUtil.getInstance().getExampleNumber("US"),
        Locale.LanguageRange.parse("en"), ClientType.IOS);
    assertEquals(Optional.of("id"), attemptData.remoteId());

    assertTrue(sinchSmsSender.checkVerificationCode("1234", attemptData.senderData()));
  }

  @ParameterizedTest
  @MethodSource
  void senderRejected(final int statusCode, final Class<? extends Throwable> expectedExceptionClass) {
    when(batchesService.send(any())).thenThrow(new ApiException(statusCode, "error"));
    assertThrows(expectedExceptionClass, () -> sinchSmsSender.sendVerificationCode(MessageTransport.SMS,
        PhoneNumberUtil.getInstance().getExampleNumber("US"),
        Locale.LanguageRange.parse("en"), ClientType.IOS));
  }

  static Stream<Arguments> senderRejected() {
    return Stream.of(
        Arguments.of(400, SenderRejectedRequestException.class),
        Arguments.of(401, SenderRejectedRequestException.class),
        Arguments.of(403, SenderRejectedRequestException.class),
        Arguments.of(429, SenderRateLimitedRequestException.class),
        Arguments.of(500, UncheckedIOException.class),
        Arguments.of(503, UncheckedIOException.class)
    );
  }

}
