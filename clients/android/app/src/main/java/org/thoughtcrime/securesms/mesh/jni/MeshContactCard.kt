/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh.jni

import org.signal.libsignal.internal.Native
import org.signal.libsignal.internal.NativeHandleGuard
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.PreKeyBundle

/**
 * A signed meshlink contact card: identity key, signed prekey, Kyber prekey, name and creation
 * time. Both signatures are verified by the Rust decoder, so a card that decodes is trustworthy
 * as far as its own identity key goes. Wraps the Rust `MeshContactCard` handle.
 */
class MeshContactCard private constructor(handle: Long) : NativeHandleGuard.SimpleOwner(handle) {

  override fun release(nativeHandle: Long) {
    Native.MeshContactCard_Destroy(nativeHandle)
  }

  fun encode(): ByteArray = guardedMap { Native.MeshContactCard_Encode(it) }

  /** URL-safe base64 of [encode]; what goes into the QR code. */
  fun toBase64(): String = guardedMap { Native.MeshContactCard_ToBase64(it) }

  val fingerprint: ByteArray
    get() = guardedMap { Native.MeshContactCard_Fingerprint(it) }

  val name: String
    get() = guardedMap { Native.MeshContactCard_Name(it) }

  val registrationId: Int
    get() = guardedMap { Native.MeshContactCard_RegistrationId(it) }

  val deviceId: Int
    get() = guardedMap { Native.MeshContactCard_DeviceId(it) }

  /** Seconds since the epoch. */
  val createdAt: Long
    get() = guardedMap { Native.MeshContactCard_CreatedAt(it) }

  val identityKey: IdentityKey
    get() = IdentityKey(ECPublicKey(guardedMap { Native.MeshContactCard_IdentityKey(it) }))

  /** The libsignal prekey bundle to start a session with this contact using the app's own stores. */
  fun preKeyBundle(): PreKeyBundle = MeshNativeBridge.preKeyBundleFromHandle(guardedMap { Native.MeshContactCard_PreKeyBundle(it) })

  /**
   * The fingerprint as 32 lowercase hex characters, the name meshlink's internal-crypto mode uses
   * for the contact's `ProtocolAddress`. The app stores sessions under the UUID rendering of the
   * same bytes instead (see `MeshContacts`), because its identity store parses address names as
   * service ids.
   */
  val addressName: String
    get() = guardedMap { Native.MeshContactCard_AddressName(it) }

  fun safetyNumber(theirs: MeshContactCard): String {
    return guardedMap { mine -> theirs.guardedMap { other -> Native.MeshContactCard_SafetyNumber(mine, other) } }
  }

  companion object {
    fun decode(data: ByteArray): MeshContactCard = MeshContactCard(Native.MeshContactCard_Decode(data))

    fun fromBase64(text: String): MeshContactCard = MeshContactCard(Native.MeshContactCard_FromBase64(text))
  }
}
