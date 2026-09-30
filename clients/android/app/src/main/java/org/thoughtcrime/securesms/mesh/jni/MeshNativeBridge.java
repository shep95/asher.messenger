/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh.jni;

import org.signal.libsignal.protocol.state.PreKeyBundle;

/**
 * Java shim for the one libsignal wrapper whose handle constructor is Kotlin {@code internal}.
 * Kotlin compiles internal constructors to public JVM constructors (the bridge itself calls it
 * through reflection), so Java can call it while Kotlin in another module cannot.
 */
final class MeshNativeBridge {
  private MeshNativeBridge() {}

  static PreKeyBundle preKeyBundleFromHandle(long handle) {
    return new PreKeyBundle(handle);
  }
}
