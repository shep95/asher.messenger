/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.infobip;

import com.infobip.ApiException;
import com.infobip.model.MessageStatus;
import io.micronaut.http.HttpStatus;
import jakarta.validation.constraints.NotNull;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.signal.registration.sender.SenderFraudBlockException;
import org.signal.registration.sender.SenderRejectedRequestException;
import org.signal.registration.util.CompletionExceptions;

public class InfobipExceptions {
  private static final Set<HttpStatus> REJECTED_HTTP_STATUS_CODES = Set.of(
      HttpStatus.BAD_REQUEST,
      HttpStatus.UNAUTHORIZED
  );

  // See https://www.infobip.com/docs/essentials/response-status-and-error-codes
  private static final Set<Integer> REJECTED_GROUP_IDS = Set.of(
      2, // Message not delivered
      4, // Expired
      5  // Rejected by Infobip
  );

  /**
   * Attempts to map an Infobip {@link ApiException} to a more specific {@link SenderRejectedRequestException} subclass.
   *
   * @param apiException the {@code ApiException} to map to a more specific exception type
   *
   * @return a more specific exception if a mapping could be calculated or empty otherwise
   */
  public static Optional<SenderRejectedRequestException> toSenderRejectedException(final ApiException apiException) {
    Optional<HttpStatus> maybeHttpStatus;

    try {
      maybeHttpStatus = Optional.of(HttpStatus.valueOf(apiException.responseStatusCode()));
    } catch (final IllegalArgumentException ignored) {
      maybeHttpStatus = Optional.empty();
    }

    if (maybeHttpStatus.map(REJECTED_HTTP_STATUS_CODES::contains).orElse(false)) {
      return Optional.of(new SenderRejectedRequestException(apiException));
    }

    return Optional.empty();
  }

  public static @Nullable String getErrorCode(@NotNull final Throwable throwable) {
    Throwable unwrapped = CompletionExceptions.unwrap(throwable);

    while (!(unwrapped instanceof ApiException) && unwrapped.getCause() != null) {
      unwrapped = unwrapped.getCause();
    }

    if (unwrapped instanceof ApiException apiException) {
      return String.valueOf(apiException.responseStatusCode());
    }

    if (unwrapped instanceof InfobipRejectedRequestException infobipException) {
      return infobipException.getStatusCode();
    }

    return null;
  }

  public static void maybeThrowInfobipRejectedRequestException(final MessageStatus status) throws InfobipRejectedRequestException {
    if (REJECTED_GROUP_IDS.contains(status.getGroupId())) {
      throw new InfobipRejectedRequestException(status);
    }
  }
}
