/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.messagebird;

import com.messagebird.exceptions.GeneralException;
import com.messagebird.exceptions.MessageBirdException;
import com.messagebird.exceptions.NotFoundException;
import com.messagebird.exceptions.UnauthorizedException;
import com.messagebird.objects.ErrorReport;
import io.micronaut.http.HttpStatus;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.signal.registration.sender.SenderRejectedRequestException;
import org.signal.registration.util.CompletionExceptions;

public class MessageBirdExceptions {

  private static final Set<Integer> REJECTED_REQUEST_ERROR_CODES = Set.of(
      2,  // Request not allowed
      9,  // Missing params
      10, // Invalid params
      20, // Not found
      21, // Bad request
      101 // Duplicate entry
  );

  private static final Set<HttpStatus> REJECTED_REQUEST_HTTP_CODES = Set.of(
      HttpStatus.UNPROCESSABLE_ENTITY,
      HttpStatus.TOO_MANY_REQUESTS
  );

  /**
   * Attempts to map a {@link MessageBirdException} to a more specific {@link SenderRejectedRequestException} subclass.
   *
   * @param messageBirdException the exception to map to a more specific exception type
   *
   * @return a more specific exception if a mapping could be calculated or empty otherwise
   */
  public static Optional<SenderRejectedRequestException> toSenderRejectedException(final MessageBirdException messageBirdException) {
    // First check for any messagebird specific api errors we are interested in
    if (errorReports(messageBirdException).stream().map(ErrorReport::getCode).anyMatch(REJECTED_REQUEST_ERROR_CODES::contains)) {
      return Optional.of(new SenderRejectedRequestException(messageBirdException));
    }

    return httpError(messageBirdException)
        .filter(REJECTED_REQUEST_HTTP_CODES::contains)
        .map(ignored -> new SenderRejectedRequestException(messageBirdException));
  }

  public static @Nullable String extract(@NotNull Throwable throwable) {
    throwable = CompletionExceptions.unwrap(throwable);
    throwable = unwrap(throwable);

    final List<ErrorReport> errorsReports = errorReports(throwable);
    if (!errorsReports.isEmpty()) {
      return String.valueOf(errorsReports.getFirst().getCode());
    }

    final Optional<HttpStatus> httpError = httpError(throwable);
    if (httpError.isPresent()) {
      return String.valueOf(httpError.get().getCode());
    }
    if (throwable instanceof NotFoundException) {
      return "notFound";
    }
    if (throwable instanceof UnauthorizedException) {
      return "unauthorized";
    }
    return null;
  }

  /**
   * If throwable is a {@link SenderRejectedRequestException}, unwrap and get the cause
   */
  private static Throwable unwrap(Throwable throwable) {
    while (throwable instanceof SenderRejectedRequestException e && throwable.getCause() != null) {
      throwable = e.getCause();
    }
    return throwable;
  }

  private static Optional<HttpStatus> httpError(final Throwable throwable) {
    if (throwable instanceof GeneralException generalException && generalException.getResponseCode() != null) {
      try {
        return Optional.of(HttpStatus.valueOf(generalException.getResponseCode()));
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  private static List<ErrorReport> errorReports(final Throwable throwable) {
    if (throwable instanceof MessageBirdException mbException && mbException.getErrors() != null) {
      return mbException.getErrors();
    }
    return Collections.emptyList();
  }
}
