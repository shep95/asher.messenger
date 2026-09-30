/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.filters;

import static org.whispersystems.textsecuregcm.metrics.MetricsUtil.name;

import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.Metrics;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import org.whispersystems.textsecuregcm.util.InetAddressRange;

/// Applies the `externalRequestFilter.paths` internal-network restriction to requests that arrive through the
/// authenticated websocket. Those requests are synthesised by `WebSocketResourceProvider` and dispatched straight to
/// Jersey, so they never traverse the servlet filter chain that carries [ExternalRequestFilter]; without this filter
/// any authenticated client could reach an "internal only" path from any network by tunnelling it over the socket.
///
/// Path patterns use the same servlet-style syntax as the configuration: an exact path (`/v1/foo`), a prefix pattern
/// (`/v1/foo/*`) or an extension pattern (`*.json`).
public class ExternalRequestPathFilter implements ContainerRequestFilter {

  private static final String REQUESTS_COUNTER_NAME = name(ExternalRequestFilter.class, "requests");
  private static final String PROTOCOL_TAG_NAME = "protocol";
  private static final String BLOCKED_TAG_NAME = "blocked";

  private final ExternalRequestFilter externalRequestFilter;
  private final List<String> pathPatterns;

  public ExternalRequestPathFilter(final Set<InetAddressRange> permittedInternalAddressRanges,
      final Set<String> pathPatterns) {
    this.externalRequestFilter = new ExternalRequestFilter(permittedInternalAddressRanges, Set.of());
    this.pathPatterns = List.copyOf(pathPatterns);
  }

  @Override
  public void filter(final ContainerRequestContext requestContext) {
    final String path = "/" + requestContext.getUriInfo().getPath();

    if (pathPatterns.stream().noneMatch(pattern -> matches(pattern, path))) {
      return;
    }

    final boolean blocked = shouldBlock(
        (String) requestContext.getProperty(RemoteAddressFilter.REMOTE_ADDRESS_ATTRIBUTE_NAME));

    Metrics.counter(REQUESTS_COUNTER_NAME,
            PROTOCOL_TAG_NAME, "websocket",
            BLOCKED_TAG_NAME, String.valueOf(blocked))
        .increment();

    if (blocked) {
      requestContext.abortWith(Response.status(Response.Status.NOT_FOUND).build());
    }
  }

  private boolean shouldBlock(final String remoteAddress) {
    if (remoteAddress == null) {
      // Fail closed: a request with no attributable remote address is never "internal"
      return true;
    }

    try {
      return externalRequestFilter.shouldBlock(InetAddress.getByName(remoteAddress));
    } catch (final UnknownHostException e) {
      return true;
    }
  }

  @VisibleForTesting
  static boolean matches(final String pattern, final String path) {
    if (pattern.endsWith("/*")) {
      final String prefix = pattern.substring(0, pattern.length() - 2);
      return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    if (pattern.startsWith("*.")) {
      return path.endsWith(pattern.substring(1));
    }

    return path.equals(pattern);
  }
}
