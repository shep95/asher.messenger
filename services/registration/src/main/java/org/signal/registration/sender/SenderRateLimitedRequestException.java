/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender;

import java.time.Duration;

public class SenderRateLimitedRequestException extends SenderRejectedRequestException {

  private final Duration retryAfter;

  public SenderRateLimitedRequestException(final Duration retryAfter) {
    this.retryAfter = retryAfter;
  }

  public Duration getRetryAfter() {
    return retryAfter;
  }
}
