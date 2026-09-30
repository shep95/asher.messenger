/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session.bigtable;

import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.cloud.bigtable.data.v2.models.ConditionalRowMutation;
import com.google.cloud.bigtable.data.v2.models.Filters;
import com.google.cloud.bigtable.data.v2.models.Mutation;
import com.google.cloud.bigtable.data.v2.models.Query;
import com.google.cloud.bigtable.data.v2.models.Row;
import com.google.cloud.bigtable.data.v2.models.RowCell;
import com.google.cloud.bigtable.data.v2.models.RowMutation;
import com.google.cloud.bigtable.data.v2.models.TableId;
import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionCompletedEvent;
import org.signal.registration.session.SessionMetadata;
import org.signal.registration.session.SessionNotFoundException;
import org.signal.registration.session.SessionRepository;
import org.signal.registration.util.UUIDUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Bigtable session repository stores sessions in a <a href="https://cloud.google.com/bigtable">Cloud Bigtable</a>
 * table. This repository stores each session in its own row identified by the {@link ByteString} form of the session's
 * ID. This repository will periodically query for and discard expired sessions, but as a safety measure, it also
 * expects that the Bigtable column family has a garbage collection policy that will automatically remove stale sessions
 * after some amount of time.
 *
 * @see <a href="https://cloud.google.com/bigtable/docs">Cloud Bigtable documentation</a>
 * @see <a href="https://cloud.google.com/bigtable/docs/garbage-collection">About garbage collection</a>
 */
@Singleton
@Primary
@Requires(bean = BigtableDataClient.class)
class BigtableSessionRepository implements SessionRepository {

  private final BigtableDataClient bigtableDataClient;
  private final ApplicationEventPublisher<SessionCompletedEvent> sessionCompletedEventPublisher;
  private final Clock clock;
  
  private final TableId tableId;
  private final String columnFamilyName;

  private final Timer createSessionTimer;
  private final Timer getSessionTimer;
  private final Timer updateSessionTimer;
  private final Timer deleteSessionTimer;

  @VisibleForTesting
  static final ByteString DATA_COLUMN_NAME = ByteString.copyFromUtf8("D");

  private static final ByteString REMOVAL_COLUMN_NAME = ByteString.copyFromUtf8("R");

  @VisibleForTesting
  static final Duration REMOVAL_TTL_PADDING = Duration.ofMinutes(5);

  @VisibleForTesting
  static final int MAX_UPDATE_ATTEMPTS = 3;

  private static final ByteString EPOCH_BYTE_STRING = instantToByteString(Instant.EPOCH);

  private static final Logger logger = LoggerFactory.getLogger(BigtableSessionRepository.class);

  public BigtableSessionRepository(final BigtableDataClient bigtableDataClient,
      final ApplicationEventPublisher<SessionCompletedEvent> sessionCompletedEventPublisher,
      final BigtableSessionRepositoryConfiguration configuration,
      final Clock clock,
      final MeterRegistry meterRegistry) {

    this.bigtableDataClient = bigtableDataClient;
    this.sessionCompletedEventPublisher = sessionCompletedEventPublisher;
    this.clock = clock;
    
    this.tableId = TableId.of(configuration.tableName());
    this.columnFamilyName = configuration.columnFamilyName();

    this.createSessionTimer = meterRegistry.timer(MetricsUtil.name(getClass(), "createSession"));
    this.getSessionTimer = meterRegistry.timer(MetricsUtil.name(getClass(), "getSession"));
    this.updateSessionTimer = meterRegistry.timer(MetricsUtil.name(getClass(), "updateSession"));
    this.deleteSessionTimer = meterRegistry.timer(MetricsUtil.name(getClass(), "deleteSession"));
  }

  @Scheduled(fixedDelay = "${session-repository.bigtable.remove-expired-sessions-interval:10s}")
  @VisibleForTesting
  void deleteExpiredSessions() {
    deleteExpiredSessions(getSessionsPendingRemoval());
  }

  @VisibleForTesting
  void deleteExpiredSessions(final Stream<RegistrationSession> sessionsPendingRemoval) {
    sessionsPendingRemoval.forEach(session -> {
      if (removeExpiredSession(session)) {
        try {
          sessionCompletedEventPublisher.publishEvent(new SessionCompletedEvent(session));
        } catch (Exception e) {
          logger.error("Error publishing SessionCompletion event", e);
        }
      }
    });
  }

  private Stream<RegistrationSession> getSessionsPendingRemoval() {
    // Sessions who are going to expire in the next REMOVAL_TTL_PADDING time interval
    // are eligible to be processed. If we fail to remove these sessions, Bigtable will
    // remove them after REMOVAL_TTL_PADDING time has elapsed
    final Filters.ValueRangeFilter removalTimeRange = Filters.FILTERS.value().range().of(
        EPOCH_BYTE_STRING,
        instantToByteString(clock.instant().plus(REMOVAL_TTL_PADDING)));

    return bigtableDataClient.readRows(Query.create(tableId)
        .filter(Filters.FILTERS.condition(Filters.FILTERS.chain()
                .filter(Filters.FILTERS.family().exactMatch(columnFamilyName))
                .filter(Filters.FILTERS.qualifier().exactMatch(REMOVAL_COLUMN_NAME))
                .filter(removalTimeRange))
            .then(Filters.FILTERS.chain()
                .filter(Filters.FILTERS.pass())
                .filter(Filters.FILTERS.limit().cellsPerColumn(1)))))
        .stream()
        .map(row -> {
          try {
            return extractSession(row);
          } catch (final SessionNotFoundException e) {
            return null;
          }
        })
        .filter(Objects::nonNull);
  }

  @VisibleForTesting
  boolean removeExpiredSession(final RegistrationSession session) {
    final Timer.Sample sample = Timer.start();

    final boolean deleted = bigtableDataClient.checkAndMutateRow(ConditionalRowMutation.create(tableId, session.getId())
        .condition(Filters.FILTERS.chain()
            .filter(Filters.FILTERS.family().exactMatch(columnFamilyName))
            .filter(Filters.FILTERS.qualifier().exactMatch(DATA_COLUMN_NAME))
            .filter(Filters.FILTERS.value().exactMatch(session.toByteString())))
        .then(Mutation.create().deleteRow()));

    if (deleted) {
      sample.stop(deleteSessionTimer);
    }

    return deleted;
  }

  @Override
  public RegistrationSession createSession(final Phonenumber.PhoneNumber phoneNumber,
      final SessionMetadata sessionMetadata,
      final Instant expiration) {

    return createSessionTimer.record(() -> {
      final UUID sessionId = UUID.randomUUID();

      final RegistrationSession session = RegistrationSession.newBuilder()
          .setId(UUIDUtil.uuidToByteString(sessionId))
          .setPhoneNumber(PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164))
          .setCreatedEpochMillis(clock.instant().toEpochMilli())
          .setExpirationEpochMillis(expiration.toEpochMilli())
          .setSessionMetadata(sessionMetadata)
          .build();

      bigtableDataClient.mutateRow(RowMutation.create(tableId, UUIDUtil.uuidToByteString(sessionId))
          .setCell(columnFamilyName, DATA_COLUMN_NAME, session.toByteString())
          .setCell(columnFamilyName, REMOVAL_COLUMN_NAME, instantToByteString(expiration.plus(REMOVAL_TTL_PADDING))));

      return session;
    });
  }

  @Override
  public RegistrationSession getSession(final UUID sessionId) throws SessionNotFoundException {
    final Timer.Sample sample = Timer.start();

    try {
      final Row row = bigtableDataClient.readRow(tableId,
          UUIDUtil.uuidToByteString(sessionId),
          Filters.FILTERS.limit().cellsPerColumn(1));

      final RegistrationSession registrationSession = extractSession(row);

      if (Instant.ofEpochMilli(registrationSession.getExpirationEpochMillis()).isBefore(clock.instant())) {
        throw new SessionNotFoundException();
      }

      return registrationSession;
    } finally {
      sample.stop(getSessionTimer);
    }
  }

  @Override
  public RegistrationSession updateSession(final UUID sessionId,
      final Function<RegistrationSession, RegistrationSession> sessionUpdater) throws SessionNotFoundException {

    final Timer.Sample sample = Timer.start();

    try {
      for (int attempt = 0; attempt < MAX_UPDATE_ATTEMPTS; attempt++) {
        final RegistrationSession originalSession = getSession(sessionId);
        final RegistrationSession updatedSession = sessionUpdater.apply(originalSession);
        final Instant expiration = Instant.ofEpochMilli(updatedSession.getExpirationEpochMillis());

        final boolean success = bigtableDataClient.checkAndMutateRow(
            ConditionalRowMutation.create(tableId, UUIDUtil.uuidToByteString(sessionId))
                .condition(Filters.FILTERS.chain()
                    .filter(Filters.FILTERS.family().exactMatch(columnFamilyName))
                    .filter(Filters.FILTERS.qualifier().exactMatch(DATA_COLUMN_NAME))
                    .filter(Filters.FILTERS.value().exactMatch(originalSession.toByteString())))
                .then(Mutation.create()
                    .setCell(columnFamilyName, DATA_COLUMN_NAME, updatedSession.toByteString())
                    .setCell(columnFamilyName, REMOVAL_COLUMN_NAME, instantToByteString(expiration.plus(REMOVAL_TTL_PADDING)))));

        if (success) {
          return updatedSession;
        }
      }

      throw new RuntimeException("Retries exhausted when updating session");
    } finally {
      sample.stop(updateSessionTimer);
    }
  }

  private RegistrationSession extractSession(@Nullable final Row row) throws SessionNotFoundException {
    if (row == null) {
      throw new SessionNotFoundException();
    }

    final List<RowCell> cells = row.getCells(columnFamilyName, DATA_COLUMN_NAME);

    if (cells.isEmpty()) {
      logger.error("Row did not contain any session data cells");
      throw new SessionNotFoundException();
    } else if (cells.size() > 1) {
      logger.warn("Row contained multiple session data cells: {}", row);
    }

    try {
      return RegistrationSession.parseFrom(cells.getFirst().getValue());
    } catch (final InvalidProtocolBufferException e) {
      logger.error("Failed to parse registration session", e);
      throw new UncheckedIOException(e);
    }
  }

  @VisibleForTesting
  static ByteString instantToByteString(final Instant instant) {
    final ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
    buffer.putLong(instant.toEpochMilli());
    buffer.flip();

    return ByteString.copyFrom(buffer);
  }
}
