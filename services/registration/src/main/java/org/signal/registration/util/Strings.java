/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class Strings {

  private Strings() {
  }

  /**
   * Compares two strings in constant time
   *
   * @see java.security.MessageDigest#isEqual(byte[], byte[])
   */
  public static boolean equalsConstantTime(final String s1, final String s2) {
    return MessageDigest.isEqual(s1.getBytes(StandardCharsets.UTF_8), s2.getBytes(StandardCharsets.UTF_8));
  }

}
