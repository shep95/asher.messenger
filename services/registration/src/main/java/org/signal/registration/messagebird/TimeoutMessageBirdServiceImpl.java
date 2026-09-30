/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.messagebird;

import com.messagebird.MessageBirdServiceImpl;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.Map;

/**
 * A {@link com.messagebird.MessageBirdService} implementation that allows callers to specify connect/read timeouts. The
 * default implementation does not have timeouts of any kind and can hang indefinitely.
 *
 * @see <a href="https://github.com/messagebird/java-rest-api/issues/205">Connection timed out (Read failed)</a>
 */
class TimeoutMessageBirdServiceImpl extends MessageBirdServiceImpl {

  private final int connectTimeoutMillis;
  private final int readTimeoutMillis;

  public TimeoutMessageBirdServiceImpl(final String accessKey,
      final Duration connectTimeout,
      final Duration readTimeout) {

    super(accessKey);

    this.connectTimeoutMillis = Math.toIntExact(connectTimeout.toMillis());
    this.readTimeoutMillis = Math.toIntExact(readTimeout.toMillis());
  }

  @Override
  public <P> HttpURLConnection getConnection(final String serviceUrl, final P body, final String requestType)
      throws IOException {

    final HttpURLConnection connection = super.getConnection(serviceUrl, body, requestType);
    connection.setConnectTimeout(connectTimeoutMillis);
    connection.setReadTimeout(readTimeoutMillis);

    return connection;
  }

  @Override
  public <P> HttpURLConnection getConnection(final String serviceUrl,
      final P body,
      final String requestType,
      final Map<String, String> headers) throws IOException {

    final HttpURLConnection connection =  super.getConnection(serviceUrl, body, requestType, headers);
    connection.setConnectTimeout(connectTimeoutMillis);
    connection.setReadTimeout(readTimeoutMillis);

    return connection;
  }
}
