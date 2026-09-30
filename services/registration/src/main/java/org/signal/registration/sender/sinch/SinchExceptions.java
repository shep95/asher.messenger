/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sender.sinch;

import com.sinch.sdk.core.exceptions.ApiException;
import io.micronaut.http.HttpStatus;
import org.signal.registration.sender.SenderRateLimitedRequestException;
import org.signal.registration.sender.SenderRejectedRequestException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

public class SinchExceptions {

  // See https://developers.sinch.com/docs/sms/api-reference/status-codes/
  private static final Set<HttpStatus> REJECTED_HTTP_STATUS_CODES = Set.of(
      HttpStatus.BAD_REQUEST,
      HttpStatus.UNAUTHORIZED,
      HttpStatus.FORBIDDEN
  );

  private static final Duration EXTERNAL_RETRY_INTERVAL = Duration.ofMinutes(1);

  public static Optional<SenderRejectedRequestException> toSenderRejectedException(final ApiException e) {
    final HttpStatus httpStatus = HttpStatus.valueOf(e.getCode());
    if (httpStatus == HttpStatus.TOO_MANY_REQUESTS) {
      return Optional.of(new SenderRateLimitedRequestException(EXTERNAL_RETRY_INTERVAL));
    }
    return REJECTED_HTTP_STATUS_CODES.contains(HttpStatus.valueOf(e.getCode()))
        ? Optional.of(new SenderRejectedRequestException(e))
        : Optional.empty();
  }

}
