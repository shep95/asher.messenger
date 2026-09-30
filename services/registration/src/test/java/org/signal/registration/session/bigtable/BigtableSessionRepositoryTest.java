/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session.bigtable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.cloud.bigtable.admin.v2.BigtableTableAdminClient;
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminSettings;
import com.google.cloud.bigtable.admin.v2.models.CreateTableRequest;
import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.cloud.bigtable.data.v2.BigtableDataSettings;
import com.google.cloud.bigtable.data.v2.models.Row;
import com.google.cloud.bigtable.data.v2.models.RowCell;
import com.google.cloud.bigtable.data.v2.models.TableId;
import com.google.cloud.bigtable.emulator.v2.Emulator;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.context.event.ApplicationEventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.session.AbstractSessionRepositoryTest;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionCompletedEvent;
import org.signal.registration.session.SessionMetadata;
import org.signal.registration.session.SessionNotFoundException;
import org.signal.registration.session.SessionRepository;
import org.signal.registration.util.UUIDUtil;

class BigtableSessionRepositoryTest extends AbstractSessionRepositoryTest {

  private Emulator emulator;
  private BigtableDataClient bigtableDataClient;
  private ApplicationEventPublisher<SessionCompletedEvent> sessionCompletedEventPublisher;

  private BigtableSessionRepository sessionRepository;

  private static final String PROJECT_ID = "test";
  private static final String INSTANCE_ID = "test";

  private static final String TABLE_ID = "sessions";
  private static final String COLUMN_FAMILY_NAME = "S";

  @BeforeEach
  protected void setUp() throws Exception {
    super.setUp();

    emulator = Emulator.createBundled();
    emulator.start();

    try (final BigtableTableAdminClient tableAdminClient =
        BigtableTableAdminClient.create(BigtableTableAdminSettings.newBuilderForEmulator(emulator.getPort())
            .setProjectId(PROJECT_ID)
            .setInstanceId(INSTANCE_ID)
            .build())) {

      tableAdminClient.createTable(CreateTableRequest.of(TABLE_ID).addFamily(COLUMN_FAMILY_NAME));
    }

    bigtableDataClient = BigtableDataClient.create(BigtableDataSettings.newBuilderForEmulator(emulator.getPort())
        .setProjectId(PROJECT_ID)
        .setInstanceId(INSTANCE_ID)
        .build());

    //noinspection unchecked
    sessionCompletedEventPublisher = mock(ApplicationEventPublisher.class);

    sessionRepository = new BigtableSessionRepository(bigtableDataClient,
        sessionCompletedEventPublisher,
        new BigtableSessionRepositoryConfiguration(TABLE_ID, COLUMN_FAMILY_NAME),
        getClock(),
        new SimpleMeterRegistry());
  }

  @AfterEach
  void tearDown() {
    bigtableDataClient.close();
    emulator.stop();
  }

  @Override
  protected SessionRepository getRepository() {
    return sessionRepository;
  }

  @Test
  void getSessionExpired() {
    final RegistrationSession expiredSession = sessionRepository.createSession(
        PhoneNumberUtil.getInstance().getExampleNumber("US"),
        SessionMetadata.newBuilder().build(),
        getClock().instant().minusSeconds(1));

    assertThrows(SessionNotFoundException.class,
        () -> sessionRepository.getSession(UUIDUtil.uuidFromByteString(expiredSession.getId())));
  }

  @Test
  void updateSessionWithRetry() {
    final BigtableDataClient mockBigtableClient = mock(BigtableDataClient.class);

    final BigtableSessionRepository retryRepository = new BigtableSessionRepository(mockBigtableClient,
        sessionCompletedEventPublisher, new BigtableSessionRepositoryConfiguration(TABLE_ID, COLUMN_FAMILY_NAME),
        getClock(),
        new SimpleMeterRegistry());

    final UUID sessionId = UUID.randomUUID();

    final RegistrationSession session = RegistrationSession.newBuilder()
        .setId(UUIDUtil.uuidToByteString(sessionId))
        .setExpirationEpochMillis(getClock().instant().plusSeconds(1).toEpochMilli())
        .build();

    final Row row = buildRowForSession(session);

    when(mockBigtableClient.readRow(any(TableId.class), any(ByteString.class), any()))
        .thenReturn(row);

    when(mockBigtableClient.checkAndMutateRow(any()))
        .thenReturn(false)
        .thenReturn(true);

    assertDoesNotThrow(() -> retryRepository.updateSession(sessionId, Function.identity()));

    verify(mockBigtableClient, times(2)).checkAndMutateRow(any());
  }

  @Test
  void updateSessionRetriesExhausted() {
    final BigtableDataClient mockBigtableClient = mock(BigtableDataClient.class);

    final BigtableSessionRepository retryRepository = new BigtableSessionRepository(mockBigtableClient,
        sessionCompletedEventPublisher,
        new BigtableSessionRepositoryConfiguration(TABLE_ID, COLUMN_FAMILY_NAME),
        getClock(), new SimpleMeterRegistry());

    final UUID sessionId = UUID.randomUUID();

    final RegistrationSession session = RegistrationSession.newBuilder()
        .setId(UUIDUtil.uuidToByteString(sessionId))
        .setExpirationEpochMillis(getClock().instant().plusSeconds(1).toEpochMilli())
        .build();

    final Row row = buildRowForSession(session);

    when(mockBigtableClient.readRow(any(TableId.class), any(ByteString.class), any())).thenReturn(row);
    when(mockBigtableClient.checkAndMutateRow(any())).thenReturn(false);

    assertThrows(RuntimeException.class,
        () -> retryRepository.updateSession(sessionId, Function.identity()));

    verify(mockBigtableClient, times(BigtableSessionRepository.MAX_UPDATE_ATTEMPTS)).checkAndMutateRow(any());
  }

  private Row buildRowForSession(final RegistrationSession session) {
    final Row row = mock(Row.class);

    final RowCell expirationCell = mock(RowCell.class);

    final Instant currentTime = getClock().instant();
    when(expirationCell.getValue())
        .thenReturn(BigtableSessionRepository.instantToByteString(currentTime.plus(Duration.ofSeconds(1))));

    final RowCell sessionDataCell = mock(RowCell.class);
    when(sessionDataCell.getValue()).thenReturn(session.toByteString());

    when(row.getCells(COLUMN_FAMILY_NAME, BigtableSessionRepository.DATA_COLUMN_NAME))
        .thenReturn(List.of(sessionDataCell));

    return row;
  }

  @Test
  void deleteExpiredSessions() {
    final Instant currentTime = getClock().instant();

    final RegistrationSession expiredSession = sessionRepository.createSession(
        PhoneNumberUtil.getInstance().getExampleNumber("US"),
        SessionMetadata.newBuilder().build(),
        currentTime.minus(BigtableSessionRepository.REMOVAL_TTL_PADDING.multipliedBy(2)));

    // Add a non-expired session which should not appear in the list of expired sessions
    sessionRepository.createSession(
        PhoneNumberUtil.getInstance().getExampleNumber("GB"),
        SessionMetadata.newBuilder().build(),
        currentTime.plus(BigtableSessionRepository.REMOVAL_TTL_PADDING.multipliedBy(2)));

    sessionRepository.deleteExpiredSessions();

    assertThrows(SessionNotFoundException.class,
        () -> sessionRepository.getSession(UUIDUtil.uuidFromByteString(expiredSession.getId())));

    verify(sessionCompletedEventPublisher).publishEvent(new SessionCompletedEvent(expiredSession));
  }

  @Test
  void deleteExpiredSessionsContested() {
    final Instant currentTime = getClock().instant();

    final RegistrationSession expiredSession = sessionRepository.createSession(
        PhoneNumberUtil.getInstance().getExampleNumber("US"),
        SessionMetadata.newBuilder().build(),
        currentTime.minus(BigtableSessionRepository.REMOVAL_TTL_PADDING.multipliedBy(2)));

    final RegistrationSession contestedExpiredSession = sessionRepository.createSession(
        PhoneNumberUtil.getInstance().getExampleNumber("GB"),
        SessionMetadata.newBuilder().build(),
        currentTime.minus(BigtableSessionRepository.REMOVAL_TTL_PADDING.multipliedBy(2)));

    // Remove the contested session to simulate multiple processes making a "remove expired sessions" pass in parallel
    assertTrue(sessionRepository.removeExpiredSession(contestedExpiredSession));

    sessionRepository.deleteExpiredSessions(Stream.of(expiredSession, contestedExpiredSession));

    assertThrows(SessionNotFoundException.class,
        () -> sessionRepository.getSession(UUIDUtil.uuidFromByteString(expiredSession.getId())));

    assertThrows(SessionNotFoundException.class,
        () -> sessionRepository.getSession(UUIDUtil.uuidFromByteString(contestedExpiredSession.getId())));

    verify(sessionCompletedEventPublisher).publishEvent(new SessionCompletedEvent(expiredSession));
    verifyNoMoreInteractions(sessionCompletedEventPublisher);
  }

  @Test
  void removeExpiredSession() {
    final RegistrationSession expiredSession = sessionRepository.createSession(
        PhoneNumberUtil.getInstance().getExampleNumber("US"),
        SessionMetadata.newBuilder().build(),
        Instant.now().minus(BigtableSessionRepository.REMOVAL_TTL_PADDING.multipliedBy(2)));

    final RegistrationSession notInRepositorySession = RegistrationSession.newBuilder()
        .setId(UUIDUtil.uuidToByteString(UUID.randomUUID()))
        .build();

    assertTrue(sessionRepository.removeExpiredSession(expiredSession));
    assertFalse(sessionRepository.removeExpiredSession(notInRepositorySession));
  }
}
