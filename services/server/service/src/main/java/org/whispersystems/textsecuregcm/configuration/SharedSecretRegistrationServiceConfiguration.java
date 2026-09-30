/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.configuration;

import com.fasterxml.jackson.annotation.JsonTypeName;
import io.dropwizard.core.setup.Environment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import org.whispersystems.textsecuregcm.configuration.secrets.SecretBytes;
import org.whispersystems.textsecuregcm.configuration.secrets.SecretString;
import org.whispersystems.textsecuregcm.registration.RegistrationServiceClient;
import org.whispersystems.textsecuregcm.registration.StaticBearerTokenCallCredentials;

/// Registration-service client that authenticates with a shared bearer token instead of a Google identity token.
///
/// ```yaml
/// registrationService:
///   type: shared-secret
///   host: registration.example.org
///   port: 50051
///   sharedSecret: secret://registrationService.sharedSecret
///   registrationCaCertificate: |
///     -----BEGIN CERTIFICATE-----
///   collationKeySalt: secret://registrationService.collationKeySalt
/// ```
@JsonTypeName("shared-secret")
public record SharedSecretRegistrationServiceConfiguration(@NotBlank String host,
                                                           int port,
                                                           @NotNull SecretString sharedSecret,
                                                           @NotBlank String registrationCaCertificate,
                                                           @NotNull SecretBytes collationKeySalt) implements
    RegistrationServiceClientFactory {

  @Override
  public RegistrationServiceClient build(final Environment environment,
      final ScheduledExecutorService identityRefreshExecutor) {
    try {
      return new RegistrationServiceClient(host, port,
          new StaticBearerTokenCallCredentials(sharedSecret.value()), registrationCaCertificate,
          collationKeySalt.value());
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }
  }
}
