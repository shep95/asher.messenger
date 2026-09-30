/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.rpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.order.Ordered;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/// Rejects every gRPC call that does not present the configured shared secret as a bearer token.
///
/// Active only when `rpc.authentication.shared-secret` is set; see [RpcAuthenticationConfiguration].
@Singleton
@Requires(property = "rpc.authentication.shared-secret")
public class SharedSecretAuthenticationInterceptor implements ServerInterceptor, Ordered {

  static final Metadata.Key<String> AUTHORIZATION_KEY =
      Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

  private static final String BEARER_PREFIX = "Bearer ";

  private final byte[] expectedToken;

  public SharedSecretAuthenticationInterceptor(final RpcAuthenticationConfiguration configuration) {
    this.expectedToken = configuration.sharedSecret().getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public int getOrder() {
    // Run before every other interceptor (lower value = higher precedence)
    return HIGHEST_PRECEDENCE;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(final ServerCall<ReqT, RespT> call,
      final Metadata headers,
      final ServerCallHandler<ReqT, RespT> next) {

    if (!isAuthorized(headers.get(AUTHORIZATION_KEY))) {
      call.close(Status.UNAUTHENTICATED.withDescription("Missing or invalid bearer token"), new Metadata());
      return new ServerCall.Listener<>() {};
    }

    return next.startCall(call, headers);
  }

  boolean isAuthorized(final String authorizationHeader) {
    if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
      return false;
    }

    final byte[] presented = authorizationHeader.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expectedToken, presented);
  }
}
