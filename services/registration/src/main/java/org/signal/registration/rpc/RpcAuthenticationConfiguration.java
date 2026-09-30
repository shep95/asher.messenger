/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.rpc;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import jakarta.validation.constraints.NotBlank;

/// Configures caller authentication for the gRPC API.
///
/// The upstream deployment fronts this service with a cloud identity-aware proxy, so the service itself accepts any
/// caller. A self-hosted deployment must instead set `rpc.authentication.shared-secret`; every call then has to carry
/// `Authorization: Bearer <shared-secret>` (the chat server does this with its `shared-secret` registration client
/// configuration). Without the secret every caller could verify arbitrary phone numbers and spend SMS budget.
@Context
@ConfigurationProperties("rpc.authentication")
public record RpcAuthenticationConfiguration(@NotBlank String sharedSecret) {
}
