/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.storageservice.metrics;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HttpHeaders;
import io.dropwizard.core.setup.Environment;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.EventsHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.signal.storageservice.util.UriInfoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gathers and reports HTTP request metrics at the Jetty container level, which sits above Jersey. In order to get
 * templated Jersey request paths, it adds a {@link jakarta.ws.rs.container.ContainerResponseFilter}, in order to give
 * itself access to the template.
 */
public class MetricsHttpEventHandler extends EventsHandler {

  private static final Logger logger = LoggerFactory.getLogger(MetricsHttpEventHandler.class);

  private static final Set<String> EXPECTED_HTTP_METHODS =
      Set.of("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH");

  private final Set<String> servletPaths;

  // Note that metric names deliberately refer to an older name for this class in the interest of continuity
  @VisibleForTesting
  static final String REQUEST_COUNTER_NAME = MetricsUtil.PREFIX + ".MetricsHttpChannelListener.request";
  @VisibleForTesting
  static final String RESPONSE_BYTES_COUNTER_NAME = MetricsUtil.PREFIX + ".MetricsHttpChannelListener.responseBytes";
  @VisibleForTesting
  static final String REQUEST_BYTES_COUNTER_NAME = MetricsUtil.PREFIX + ".MetricsHttpChannelListener.requestBytes";

  @VisibleForTesting
  static final String REQUEST_INFO_PROPERTY_NAME = MetricsHttpEventHandler.class.getName() + ".requestInfo";

  @VisibleForTesting
  static final String PATH_TAG = "path";

  @VisibleForTesting
  static final String METHOD_TAG = "method";

  @VisibleForTesting
  static final String STATUS_CODE_TAG = "status";

  private final MeterRegistry meterRegistry;

  @VisibleForTesting
  MetricsHttpEventHandler(
      final Handler handler,
      final MeterRegistry meterRegistry,
      final Set<String> servletPaths) {
    super(handler);

    this.meterRegistry = meterRegistry;
    this.servletPaths = servletPaths;
  }

  /**
   * Configure a {@link MetricsHttpEventHandler}
   *
   * @param environment          A dropwizard {@link org.eclipse.jetty.util.component.Environment}
   * @param meterRegistry        The meter registry to register metrics with
   * @param servletPaths         An allow-list of paths to include in metric tags for requests that are handled by above
   *                             Jersey
   */
  public static void configure(final Environment environment,
      final MeterRegistry meterRegistry,
      final Set<String> servletPaths) {

    // register a filter that will set the initial request info
    environment.jersey().register(new SetInfoRequestFilter());

    // hook into lifecycle events, to react to the Connector being added
    environment.lifecycle().addEventListener(new LifeCycle.Listener() {
      @Override
      public void lifeCycleStarting(LifeCycle event) {
        if (event instanceof Server server) {
          server.setHandler(new MetricsHttpEventHandler(server.getHandler(), meterRegistry, servletPaths));
        }
      }
    });
  }

  private void onResponseFailure(Request request, int status, Throwable failure) {

    if (failure instanceof org.eclipse.jetty.io.EofException) {
      // the client disconnected early
      return;
    }

    final RequestInfo requestInfo = getRequestInfo(request);

    logger.warn("Response failure: {} {} ({}) [{}] ",
        requestInfo.method,
        requestInfo.path,
        requestInfo.userAgent,
        status,
        failure);
  }

  @Override
  public void onComplete(Request request, int status, HttpFields headers, Throwable failure) {

    super.onComplete(request, status, headers, failure);

    if (failure != null) {
      onResponseFailure(request, status, failure);
    }

    final RequestInfo requestInfo = getRequestInfo(request);

    final Tag platformTag = UserAgentTagUtil.getPlatformTag(requestInfo.userAgent);

    final List<Tag> tags = new ArrayList<>(5);
    tags.add(Tag.of(PATH_TAG, requestInfo.path));
    tags.add(Tag.of(METHOD_TAG, requestInfo.method));
    tags.add(Tag.of(STATUS_CODE_TAG, String.valueOf(status)));
    tags.add(platformTag);

    meterRegistry.counter(REQUEST_COUNTER_NAME, tags).increment();
    meterRegistry.counter(RESPONSE_BYTES_COUNTER_NAME, tags).increment(requestInfo.responseBytes);
    meterRegistry.counter(REQUEST_BYTES_COUNTER_NAME, tags).increment(requestInfo.requestBytes);
  }

  @Override
  protected void onRequestRead(final Request request, final Content.Chunk chunk) {
    super.onRequestRead(request, chunk);
    if (chunk != null) {
      getRequestInfo(request).requestBytes += chunk.remaining();
    }
  }

  @Override
  protected void onResponseWrite(final Request request, final boolean last, final ByteBuffer content) {
    super.onResponseWrite(request, last, content);
    if (content != null) {
      getRequestInfo(request).responseBytes += content.remaining();
    }
  }

  private RequestInfo getRequestInfo(Request request) {
    final Object obj = request.getAttribute(REQUEST_INFO_PROPERTY_NAME);
    if (obj instanceof RequestInfo requestInfo) {
      return requestInfo;
    }

    // Our ContainerResponseFilter has not run yet. It should eventually run, and will override the path we set here.
    // It may not run if this is a request handled by jetty directly or if a higher-priority filter aborted the request
    // by throwing an exception, in which case we'll use this path. To avoid giving every incorrect path a unique tag we
    // check against a configured list of paths that we know would skip the filter.
    final RequestInfo newInfo = new RequestInfo(
        Optional.ofNullable(request.getHttpURI().getPath()).filter(servletPaths::contains).orElse("unknown"),
        normalizeMethod(request.getMethod()),
        request.getHeaders().get(HttpHeaders.USER_AGENT));

    request.setAttribute(REQUEST_INFO_PROPERTY_NAME, newInfo);
    return newInfo;
  }

  @VisibleForTesting
  static class RequestInfo {

    private String path;
    private final String method;
    private final @Nullable String userAgent;

    private long requestBytes;
    private long responseBytes;

    RequestInfo(@NotNull String path, @NotNull String method, @Nullable String userAgent) {
      this.path = path;
      this.method = method;
      this.userAgent = userAgent;
      this.requestBytes = 0;
      this.responseBytes = 0;
    }

    @Override
    public boolean equals(final Object o) {
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      RequestInfo that = (RequestInfo) o;
      return requestBytes == that.requestBytes && responseBytes == that.responseBytes && Objects.equals(path, that.path)
          && Objects.equals(method, that.method) && Objects.equals(userAgent, that.userAgent);
    }

    @Override
    public int hashCode() {
      return Objects.hash(path, method, userAgent, requestBytes, responseBytes);
    }
  }

  @VisibleForTesting
  static class SetInfoRequestFilter implements ContainerResponseFilter {

    @Override
    public void filter(final ContainerRequestContext requestContext, final ContainerResponseContext responseContext) {
      // Construct the templated URI path. If no matching path is found, this will be ""
      final String path = UriInfoUtil.getPathTemplate((ExtendedUriInfo) requestContext.getUriInfo());
      final Object obj = requestContext.getProperty(REQUEST_INFO_PROPERTY_NAME);

      if (obj instanceof RequestInfo requestInfo) {
        requestInfo.path = path;
      } else {
        requestContext.setProperty(REQUEST_INFO_PROPERTY_NAME,
            new RequestInfo(path, requestContext.getMethod(), requestContext.getHeaderString(HttpHeaders.USER_AGENT)));
      }
    }
  }

  static String normalizeMethod(@Nullable final String method) {
    if (StringUtils.isBlank(method)) {
      return "unknown";
    }

    return EXPECTED_HTTP_METHODS.contains(method.toUpperCase(Locale.ROOT)) ? method : "unknown";
  }
}
