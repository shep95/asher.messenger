/*
 * Copyright 2013-2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.storageservice.metrics;

import com.vdurmont.semver4j.Semver;
import io.micrometer.core.instrument.Tag;
import org.signal.storageservice.util.ua.ClientPlatform;
import org.signal.storageservice.util.ua.UnrecognizedUserAgentException;
import org.signal.storageservice.util.ua.UserAgent;
import org.signal.storageservice.util.ua.UserAgentUtil;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Utility class for extracting platform/version metrics tags from User-Agent strings.
 */
public class UserAgentTagUtil {

  public static final String PLATFORM_TAG = "platform";
  public static final String VERSION_TAG = "clientVersion";

  private UserAgentTagUtil() {
  }

  public static Tag getPlatformTag(final String userAgentString) {
    String platform;

    try {
      platform = UserAgentUtil.parseUserAgentString(userAgentString).platform().name().toLowerCase();
    } catch (final UnrecognizedUserAgentException e) {
      platform = "unrecognized";
    }

    return Tag.of(PLATFORM_TAG, platform);
  }

  public static Optional<Tag> getClientVersionTag(final String userAgentString,
      final Map<ClientPlatform, Set<Semver>> recognizedVersionsByPlatform) {

    try {
      final UserAgent userAgent = UserAgentUtil.parseUserAgentString(userAgentString);

      final Set<Semver> recognizedVersions =
          recognizedVersionsByPlatform.getOrDefault(userAgent.platform(), Collections.emptySet());

      if (recognizedVersions.contains(userAgent.version())) {
        return Optional.of(Tag.of(VERSION_TAG, userAgent.version().toString()));
      }
    } catch (final UnrecognizedUserAgentException ignored) {
    }

    return Optional.empty();
  }
}
