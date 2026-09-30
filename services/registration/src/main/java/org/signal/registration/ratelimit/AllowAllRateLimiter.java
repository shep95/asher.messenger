/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * A trivial rate limiter implementation that allows all actions unconditionally. This implementation is intended only
 * for use in development and testing and should never be used in a production setting.
 *
 * @param <K> the type of key that identifies a rate-limited action
 */
class AllowAllRateLimiter<K> implements RateLimiter<K> {

  private final Clock clock;

  AllowAllRateLimiter(final Clock clock) {
    this.clock = clock;
  }

  @Override
  public Optional<Instant> getTimeOfNextAction(final K key) {
    return Optional.of(clock.instant());
  }

  @Override
  public void checkRateLimit(final K key) {
  }
}
