/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.registration;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import java.util.concurrent.Executor;

/// Attaches a fixed bearer token to every registration-service call. Intended for self-hosted deployments where the
/// registration service is not fronted by a cloud identity-aware proxy and instead verifies a shared secret itself
/// (see `SharedSecretAuthenticationInterceptor` in the registration service).
public class StaticBearerTokenCallCredentials extends CallCredentials {

  private static final Metadata.Key<String> AUTHORIZATION_METADATA_KEY =
      Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

  private final String headerValue;

  public StaticBearerTokenCallCredentials(final String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("Registration service shared secret must not be blank");
    }
    this.headerValue = "Bearer " + token;
  }

  @Override
  public void applyRequestMetadata(final RequestInfo requestInfo, final Executor appExecutor,
      final MetadataApplier applier) {
    final Metadata metadata = new Metadata();
    metadata.put(AUTHORIZATION_METADATA_KEY, headerValue);
    applier.apply(metadata);
  }
}
