/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.whispersystems.textsecuregcm.util.InetAddressRange;

class ExternalRequestPathFilterTest {

  private static final Set<InetAddressRange> INTERNAL_RANGES = Set.of(new InetAddressRange("10.0.0.0/8"));

  @ParameterizedTest
  @CsvSource({
      "/v1/internal, /v1/internal, true",
      "/v1/internal, /v1/internal/, false",
      "/v1/internal/*, /v1/internal, true",
      "/v1/internal/*, /v1/internal/thing, true",
      "/v1/internal/*, /v1/internalthing, false",
      "*.json, /v1/config.json, true",
      "*.json, /v1/config, false",
  })
  void matches(final String pattern, final String path, final boolean expected) {
    assertEquals(expected, ExternalRequestPathFilter.matches(pattern, path));
  }

  @Test
  void blocksExternalAddressOnFilteredPath() {
    final ContainerRequestContext context = requestContext("v1/internal/thing", "203.0.113.7");

    new ExternalRequestPathFilter(INTERNAL_RANGES, Set.of("/v1/internal/*")).filter(context);

    final ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
    verify(context).abortWith(response.capture());
    assertEquals(404, response.getValue().getStatus());
  }

  @Test
  void allowsInternalAddressOnFilteredPath() {
    final ContainerRequestContext context = requestContext("v1/internal/thing", "10.1.2.3");

    new ExternalRequestPathFilter(INTERNAL_RANGES, Set.of("/v1/internal/*")).filter(context);

    verify(context, never()).abortWith(any());
  }

  @Test
  void ignoresUnfilteredPath() {
    final ContainerRequestContext context = requestContext("v1/public", "203.0.113.7");

    new ExternalRequestPathFilter(INTERNAL_RANGES, Set.of("/v1/internal/*")).filter(context);

    verify(context, never()).abortWith(any());
  }

  @Test
  void blocksWhenRemoteAddressMissing() {
    final ContainerRequestContext context = requestContext("v1/internal/thing", null);

    new ExternalRequestPathFilter(INTERNAL_RANGES, Set.of("/v1/internal/*")).filter(context);

    verify(context).abortWith(any());
  }

  private static ContainerRequestContext requestContext(final String path, final String remoteAddress) {
    final UriInfo uriInfo = mock(UriInfo.class);
    when(uriInfo.getPath()).thenReturn(path);

    final ContainerRequestContext context = mock(ContainerRequestContext.class);
    when(context.getUriInfo()).thenReturn(uriInfo);
    when(context.getProperty(RemoteAddressFilter.REMOTE_ADDRESS_ATTRIBUTE_NAME)).thenReturn(remoteAddress);

    assertTrue(path != null);
    assertFalse(path.startsWith("/"));
    return context;
  }
}
