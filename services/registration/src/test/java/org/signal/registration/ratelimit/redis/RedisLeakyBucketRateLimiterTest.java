/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit.redis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.FlushMode;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.ratelimit.LeakyBucketRateLimiterConfiguration;
import org.signal.registration.ratelimit.RateLimitExceededException;
import org.testcontainers.utility.DockerImageName;

class RedisLeakyBucketRateLimiterTest {

  private static RedisContainer redisContainer;

  private RedisClient redisClient;
  private StatefulRedisConnection<String, String> redisConnection;
  private Clock clock;

  private RedisLeakyBucketRateLimiter<String> rateLimiter;

  private static final Instant CURRENT_TIME = Instant.now().truncatedTo(ChronoUnit.MILLIS);

  private static final int MAX_PERMITS = 2;
  private static final Duration PERMIT_REGENERATION_PERIOD = Duration.ofMinutes(1);
  private static final Duration MIN_DELAY = Duration.ofSeconds(5);

  private static class TestRedisLeakyBucketRateLimiter extends RedisLeakyBucketRateLimiter<String> {

    private final boolean failOpen;

    public TestRedisLeakyBucketRateLimiter(final StatefulRedisConnection<String, String> connection,
        final Clock clock,
        final LeakyBucketRateLimiterConfiguration configuration, final boolean failOpen) {

      super(connection, clock, configuration, new SimpleMeterRegistry());
      this.failOpen = failOpen;
    }

    @Override
    protected String getBucketName(final String key) {
      return key;
    }

    @Override
    protected boolean shouldFailOpen() {
      return failOpen;
    }
  }

  @BeforeAll
  static void setUpBeforeAll() {
    // redis:6.2-alpine; please see https://hub.docker.com/layers/library/redis/6.2-alpine/images/sha256-00d4e3a0f38c4077263437a82876e719d9c2b70e4cc3dbf6654fffc3b049f5e1
    redisContainer = new RedisContainer(DockerImageName.parse("redis@sha256:347c20744e594ba14dd9768363621ac3f3e26d28085dccc01fb3acf757c9b84a"));
    redisContainer.start();
  }

  @BeforeEach
  void setUp() {
    redisClient = RedisClient.create(RedisURI.create(redisContainer.getRedisURI()));
    redisConnection = redisClient.connect();

    redisConnection.sync().flushall(FlushMode.SYNC);

    clock = mock(Clock.class);
    when(clock.instant()).thenReturn(CURRENT_TIME);

    rateLimiter = new TestRedisLeakyBucketRateLimiter(redisConnection, clock,
        new LeakyBucketRateLimiterConfiguration("session-creation", MAX_PERMITS, PERMIT_REGENERATION_PERIOD, MIN_DELAY),
        false);
  }

  @AfterEach
  void tearDown() {
    redisConnection.close();
    redisClient.close();
  }

  @AfterAll
  static void tearDownAfterAll() {
    redisContainer.stop();
  }

  @Test
  void getTimeOfNextAction() {
    assertEquals(Optional.of(CURRENT_TIME), rateLimiter.getTimeOfNextAction("test"),
        "Action for an empty bucket should be permitted immediately");

    assertDoesNotThrow(() -> rateLimiter.checkRateLimit("test"));

    assertEquals(Optional.of(CURRENT_TIME.plus(MIN_DELAY)), rateLimiter.getTimeOfNextAction("test"),
        "Action for a just-used bucket should be permitted after cooldown");

    when(clock.instant()).thenReturn(CURRENT_TIME.plus(MIN_DELAY));

    assertEquals(Optional.of(CURRENT_TIME.plus(MIN_DELAY)), rateLimiter.getTimeOfNextAction("test"));

    assertDoesNotThrow(() -> rateLimiter.checkRateLimit("test"));

    // Allow for some floating point error
    final long deviationFromExpectedMillis =
        Math.abs(rateLimiter.getTimeOfNextAction("test").orElseThrow().toEpochMilli() -
            CURRENT_TIME.plus(PERMIT_REGENERATION_PERIOD).toEpochMilli());

    assertTrue(deviationFromExpectedMillis <= 1);
  }

  @Test
  void checkRateLimit() {
    assertDoesNotThrow(() -> rateLimiter.checkRateLimit("test"),
        "Checking a rate limit for a fresh key should succeed");

    {
      final RateLimitExceededException rateLimitExceededException =
          assertThrows(RateLimitExceededException.class, () -> rateLimiter.checkRateLimit("test"),
              "Checking a rate limit twice immediately should trigger the cooldown period");

      assertEquals(Optional.of(MIN_DELAY), rateLimitExceededException.getRetryAfterDuration());
    }

    when(clock.instant()).thenReturn(CURRENT_TIME.plus(MIN_DELAY));

    assertDoesNotThrow(() -> rateLimiter.checkRateLimit("test"),
        "Checking a rate limit after cooldown has elapsed should succeed");

    when(clock.instant()).thenReturn(CURRENT_TIME.plus(MIN_DELAY.multipliedBy(2)));

    {
      final RateLimitExceededException rateLimitExceededException =
          assertThrows(RateLimitExceededException.class, () -> rateLimiter.checkRateLimit("test"),
              "Checking a rate limit before permits have generated should not succeed");

      assertTrue(rateLimitExceededException.getRetryAfterDuration().isPresent());

      final Duration retryAfterDuration = rateLimitExceededException.getRetryAfterDuration().get();

      // Allow for some floating point error
      final long deviationFromExpectedMillis =
          Math.abs(retryAfterDuration.toMillis() - PERMIT_REGENERATION_PERIOD.minus(MIN_DELAY.multipliedBy(2)).toMillis());

      assertTrue(deviationFromExpectedMillis <= 1);
    }
  }

  @Test
  void checkRateLimitRedisException() {
    @SuppressWarnings("unchecked") final RedisCommands<String, String> failureProneCommands = mock(RedisCommands.class);

    when(failureProneCommands.evalsha(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class)))
        .thenThrow(new RedisException("Test"));

    @SuppressWarnings("unchecked") final StatefulRedisConnection<String, String> failureProneConnection = mock(StatefulRedisConnection.class);
    when(failureProneConnection.sync()).thenReturn(failureProneCommands);

    final RedisLeakyBucketRateLimiter<String> failOpenLimiter =
        new TestRedisLeakyBucketRateLimiter(failureProneConnection,
            clock,
            new LeakyBucketRateLimiterConfiguration("session-creation", MAX_PERMITS, PERMIT_REGENERATION_PERIOD, MIN_DELAY),
            true);

    assertDoesNotThrow(() -> failOpenLimiter.checkRateLimit("fail-open"));

    final RedisLeakyBucketRateLimiter<String> failClosedLimiter =
        new TestRedisLeakyBucketRateLimiter(failureProneConnection,
            clock,
            new LeakyBucketRateLimiterConfiguration("session-creation", MAX_PERMITS, PERMIT_REGENERATION_PERIOD, MIN_DELAY),
            false);

    assertThrows(RateLimitExceededException.class, () -> failClosedLimiter.checkRateLimit("fail-closed"));
  }
}
