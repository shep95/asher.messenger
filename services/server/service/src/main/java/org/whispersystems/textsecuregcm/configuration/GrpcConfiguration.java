/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.configuration;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;

/// Configuration for the gRPC Server
///
/// @param bindAddress      The host to bind the omnibus server to
/// @param port             The port to bind the omnibus server to
/// @param websocketAddress The address of a listening websocket server for handling legacy requests
/// @param websocketPort    The port of a listening websocket server for handling legacy requests
/// @param idleTimeout      The duration after which an idle connection may be disconnected
/// @param h2c              If true, listen for plaintext h2c with prior-knowledge
/// @param acceptProxyProtocol If true, honour a PROXY protocol (v1/v2) header at the start of each connection and use
///                            the address it carries as the client address for rate limiting and internal-network
///                            checks. Only enable this when the omnibus port is reachable exclusively from a trusted
///                            load balancer, because any peer that can connect directly could otherwise claim an
///                            arbitrary source address. Defaults to false.
public record GrpcConfiguration(
    @NotNull String bindAddress,
    @NotNull Integer port,
    @NotNull String websocketAddress,
    @NotNull Integer websocketPort,
    @NotNull Duration idleTimeout,
    boolean h2c,
    boolean acceptProxyProtocol) {

  public GrpcConfiguration {
    if (bindAddress == null || bindAddress.isEmpty()) {
      bindAddress = "localhost";
    }
    if (websocketAddress == null || websocketAddress.isEmpty()) {
      websocketAddress = "localhost";
    }
    if (idleTimeout == null) {
      idleTimeout = Duration.ofMinutes(5);
    }
  }
}
