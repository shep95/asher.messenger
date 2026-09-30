/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.grpc.protobuf.ProtoUtils;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.signal.registration.rpc.CreateRegistrationSessionRequest;
import org.signal.registration.rpc.CreateRegistrationSessionResponse;

class SharedSecretAuthenticationInterceptorTest {

  private static final String SECRET = "correct horse battery staple";

  private final SharedSecretAuthenticationInterceptor interceptor =
      new SharedSecretAuthenticationInterceptor(new RpcAuthenticationConfiguration(SECRET));

  @Test
  void isAuthorized() {
    assertTrue(interceptor.isAuthorized("Bearer " + SECRET));
    assertFalse(interceptor.isAuthorized(null));
    assertFalse(interceptor.isAuthorized(""));
    assertFalse(interceptor.isAuthorized(SECRET));
    assertFalse(interceptor.isAuthorized("Bearer " + SECRET + "x"));
    assertFalse(interceptor.isAuthorized("Bearer " + SECRET.substring(1)));
    assertFalse(interceptor.isAuthorized("Basic " + SECRET));
  }

  @Test
  void interceptCallRejectsMissingToken() {
    final AtomicReference<Status> closedWith = new AtomicReference<>();
    final AtomicBoolean started = new AtomicBoolean(false);

    interceptor.interceptCall(recordingCall(closedWith), new Metadata(), (call, headers) -> {
      started.set(true);
      return new ServerCall.Listener<>() {};
    });

    assertFalse(started.get());
    assertNotNull(closedWith.get());
    assertEquals(Status.Code.UNAUTHENTICATED, closedWith.get().getCode());
  }

  @Test
  void interceptCallAcceptsValidToken() {
    final AtomicReference<Status> closedWith = new AtomicReference<>();
    final AtomicBoolean started = new AtomicBoolean(false);

    final Metadata headers = new Metadata();
    headers.put(SharedSecretAuthenticationInterceptor.AUTHORIZATION_KEY, "Bearer " + SECRET);

    interceptor.interceptCall(recordingCall(closedWith), headers, (call, h) -> {
      started.set(true);
      return new ServerCall.Listener<>() {};
    });

    assertTrue(started.get());
    assertEquals(null, closedWith.get());
  }

  private static ServerCall<CreateRegistrationSessionRequest, CreateRegistrationSessionResponse> recordingCall(
      final AtomicReference<Status> closedWith) {

    final MethodDescriptor<CreateRegistrationSessionRequest, CreateRegistrationSessionResponse> descriptor =
        MethodDescriptor.<CreateRegistrationSessionRequest, CreateRegistrationSessionResponse>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("org.signal.registration.rpc.RegistrationService/create_session")
            .setRequestMarshaller(ProtoUtils.marshaller(CreateRegistrationSessionRequest.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(CreateRegistrationSessionResponse.getDefaultInstance()))
            .build();

    return new ServerCall<>() {
      @Override
      public void request(final int numMessages) {
      }

      @Override
      public void sendHeaders(final Metadata headers) {
      }

      @Override
      public void sendMessage(final CreateRegistrationSessionResponse message) {
      }

      @Override
      public void close(final Status status, final Metadata trailers) {
        closedWith.set(status);
      }

      @Override
      public boolean isCancelled() {
        return false;
      }

      @Override
      public MethodDescriptor<CreateRegistrationSessionRequest, CreateRegistrationSessionResponse> getMethodDescriptor() {
        return descriptor;
      }
    };
  }
}
