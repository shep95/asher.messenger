/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sender.twilio;

import com.twilio.exception.ApiException;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.signal.registration.sender.SenderFraudBlockException;
import org.signal.registration.sender.SenderInvalidParametersException;
import org.signal.registration.sender.SenderRateLimitedRequestException;
import org.signal.registration.sender.SenderRejectedRequestException;
import org.signal.registration.sender.SenderRejectedTransportException;
import org.signal.registration.util.CompletionExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TwilioExceptions {

  private static final int INVALID_PARAM_ERROR_CODE = 60200;

  private static final Set<Integer> SUSPECTED_FRAUD_ERROR_CODES = Set.of(
      30450, // Message delivery blocked (SMS Pumping Protection)
      60410, // Verification delivery attempt blocked (Fraud Guard)
      60605  // Verification delivery attempt blocked (geo permissions)
  );

  private static final Set<Integer> REJECTED_REQUEST_ERROR_CODES = Set.of(
      20404, // Not found
      21211, // Invalid 'to' phone number
      21215, // Geo Permission configuration is not permitting call
      21216, // Call blocked by Twilio blocklist
      21610, // Attempt to send to unsubscribed recipient
      60200, // Invalid parameter
      60202, // Max check attempts reached
      60203, // Max send attempts reached
      60212  // Too many concurrent requests for phone number
  );

  private static final Set<Integer> REJECTED_TRANSPORT_ERROR_CODES = Set.of(
      21408, // Permission to send an SMS has not been enabled for the region indicated by the 'To' number
      21612, // The 'To' phone number is not currently reachable via SMS
      21614, // 'To' number is not a valid mobile number
      60205  // SMS is not supported by landline phone number
  );

  private static final Set<Integer> RATE_LIMIT_EXCEEDED_ERROR_CODES = Set.of(
      20429 // Too Many Requests
  );

  private static final Duration EXTERNAL_RETRY_INTERVAL = Duration.ofMinutes(1);
  private static final Logger log = LoggerFactory.getLogger(TwilioExceptions.class);

  private TwilioExceptions() {}

  public static @Nullable String extractErrorCode(@NotNull final Throwable throwable) {
    Throwable unwrapped = CompletionExceptions.unwrap(throwable);

    while (unwrapped instanceof SenderRejectedRequestException e && unwrapped.getCause() != null) {
      unwrapped = e.getCause();
    }

    if (unwrapped instanceof ApiException apiException) {
      if (apiException.getCode() != null) {
        return String.valueOf(apiException.getCode());
      } else if (apiException.getStatusCode() != null) {
        return "http_" + apiException.getStatusCode();
      } else {
        log.warn("Received twilio exception with no error code or HTTP status", apiException);
      }
    }

    return null;
  }

  private static Optional<SenderInvalidParametersException.ParamName> extractInvalidParameter(
      @NotNull final ApiException apiException) {
    // attempt to parse out specific information about why the request was rejected
    // https://www.twilio.com/docs/api/errors/60200
    final String message = apiException.getMessage();
    if (message == null) {
      return Optional.empty();
    }

    // Invalid parameter `To`: +XXXXXXXXXXX
    if (message.toLowerCase().contains("to")) {
      return Optional.of(SenderInvalidParametersException.ParamName.NUMBER);
    }

    // Invalid parameter: Locale
    if (message.toLowerCase().contains("locale")) {
      return Optional.of(SenderInvalidParametersException.ParamName.LOCALE);
    }

    return Optional.empty();
  }

  /**
   * Attempts to map a Twilio {@link ApiException} to a more specific {@link SenderRejectedRequestException} subclass.
   *
   * @param apiException the {@code ApiException} to map to a more specific exception type
   *
   * @return a more specific exception if a mapping could be calculated or empty otherwise
   */
  public static Optional<SenderRejectedRequestException> toSenderRejectedException(final ApiException apiException) {
    if (apiException.getCode() != null) {
      if (INVALID_PARAM_ERROR_CODE == apiException.getCode()) {
        return Optional.of(new SenderInvalidParametersException(apiException, extractInvalidParameter(apiException)));
      } else if (SUSPECTED_FRAUD_ERROR_CODES.contains(apiException.getCode())) {
        return Optional.of(new SenderFraudBlockException(apiException));
      } else if (REJECTED_REQUEST_ERROR_CODES.contains(apiException.getCode())) {
        return Optional.of(new SenderRejectedRequestException(apiException));
      } else if (REJECTED_TRANSPORT_ERROR_CODES.contains(apiException.getCode())) {
        return Optional.of(new SenderRejectedTransportException(apiException));
      } else if (RATE_LIMIT_EXCEEDED_ERROR_CODES.contains(apiException.getCode())) {
        return Optional.of(new SenderRateLimitedRequestException(EXTERNAL_RETRY_INTERVAL));
      }
    }
    
    return Optional.empty();
  }
}
