/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh.jni

import org.signal.libsignal.internal.Native
import org.signal.libsignal.internal.NativeHandleGuard
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * A meshlink identity: the Signal identity key pair plus one signed prekey and one Kyber prekey
 * that travel in the contact card. Wraps the Rust `MeshIdentity` handle
 * (`rust/bridge/shared/src/mesh.rs`).
 */
class MeshIdentity private constructor(handle: Long) : NativeHandleGuard.SimpleOwner(handle) {

  override fun release(nativeHandle: Long) {
    Native.MeshIdentity_Destroy(nativeHandle)
  }

  /** Opaque blob for [import]; contains private keys, store it like the other account secrets. */
  fun export(): ByteArray = guardedMap { Native.MeshIdentity_Export(it) }

  /** 16 bytes: SHA-256("meshlink-fingerprint-v1" || identity key)[..16]. */
  val fingerprint: ByteArray
    get() = guardedMap { Native.MeshIdentity_Fingerprint(it) }

  val cardBytes: ByteArray
    get() = guardedMap { Native.MeshIdentity_Card(it) }

  fun card(): MeshContactCard = MeshContactCard.decode(cardBytes)

  /** The signed prekey the card advertises; the app must hold it in its own signed prekey store. */
  fun signedPreKeyRecord(): SignedPreKeyRecord = SignedPreKeyRecord(guardedMap { Native.MeshIdentity_SignedPreKeyRecord(it) })

  /** The Kyber prekey the card advertises; store it as a last-resort key so it survives use. */
  fun kyberPreKeyRecord(): KyberPreKeyRecord = KyberPreKeyRecord(guardedMap { Native.MeshIdentity_KyberPreKeyRecord(it) })

  companion object {
    /** A brand new identity with a fresh key pair (tests and internal-crypto nodes). */
    fun generate(name: String): MeshIdentity = MeshIdentity(Native.MeshIdentity_Generate(name))

    /** Builds the mesh identity around the app's ACI identity key pair so the fingerprint is the account's. */
    fun fromIdentityKeyPair(keyPair: IdentityKeyPair, registrationId: Int, name: String): MeshIdentity {
      return MeshIdentity(Native.MeshIdentity_FromIdentityKeyPair(keyPair.serialize(), registrationId, name))
    }

    fun import(data: ByteArray): MeshIdentity = MeshIdentity(Native.MeshIdentity_Import(data))

    /** Recovers the identity inside an "ASHB" backup blob (the node is created from it, then `MeshNode.importBackup` merges the rest). */
    fun fromBackup(passphrase: String, blob: ByteArray): MeshIdentity = MeshIdentity(Native.MeshIdentity_FromBackup(passphrase, blob))
  }
}
