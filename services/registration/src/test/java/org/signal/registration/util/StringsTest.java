/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collection;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StringsTest {

  @ParameterizedTest
  @MethodSource
  void test(final String s1, final String s2, final boolean expected) {
    assertEquals(expected, Strings.equalsConstantTime(s1, s2));
  }

  static Collection<Arguments> test() {
    return List.of(
        Arguments.argumentSet("equal", "123456", "123456", true),
        Arguments.argumentSet("not equal", "123456", "123455", false)
    );
  }

}
