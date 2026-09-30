/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

/**
 * Persistent state of the offline mesh transport (see `docs/offline-mesh.md`).
 *
 * The identity export holds the mesh prekeys' private halves; it is deliberately left out of the
 * backup key list, like the other prekey material, so a restored account generates a fresh card.
 */
class MeshValues internal constructor(store: KeyValueStore) : SignalStoreValues(store) {

  companion object {
    private const val KEY_ENABLED = "mesh.enabled"
    private const val KEY_IDENTITY = "mesh.identity_export"
    private const val KEY_SIGNED_PRE_KEY_ID = "mesh.signed_pre_key_id"
    private const val KEY_KYBER_PRE_KEY_ID = "mesh.kyber_pre_key_id"
    private const val KEY_BLE_ENABLED = "mesh.ble_enabled"
    private const val KEY_USB_ENABLED = "mesh.usb_enabled"
    private const val KEY_CONFIGURE_RADIO = "mesh.configure_radio"
    private const val KEY_LAN_ENABLED = "mesh.lan_enabled"
  }

  public override fun onFirstEverAppLaunch() = Unit

  public override fun getKeysToIncludeInBackup(): List<String> = emptyList()

  /** The user's switch. The feature flag `mesh.transport` (RemoteConfig.meshTransport) gates it. */
  var enabled: Boolean by booleanValue(KEY_ENABLED, false)

  /** `MeshIdentity_Export` blob, so the same prekeys are advertised across restarts. */
  var identityExport: ByteArray? by nullableBlobValue(KEY_IDENTITY, null)

  /** Ids of the mesh prekeys installed in the ACI prekey tables; -1 when none. */
  var signedPreKeyId: Int by integerValue(KEY_SIGNED_PRE_KEY_ID, -1)
  var kyberPreKeyId: Int by integerValue(KEY_KYBER_PRE_KEY_ID, -1)

  /** Phone-to-phone Bluetooth LE link. */
  var bleEnabled: Boolean by booleanValue(KEY_BLE_ENABLED, true)

  /** TCP links to other nodes on the same Wi-Fi, found through DNS-SD (`_asher-mesh._tcp`). */
  var lanEnabled: Boolean by booleanValue(KEY_LAN_ENABLED, true)

  /** RNode-class LoRa board over USB serial. */
  var usbEnabled: Boolean by booleanValue(KEY_USB_ENABLED, true)

  /** Send `RadioConfig::EU_LONG_RANGE` to an RNode on connect. Off by default: the legal band is the operator's call. */
  var configureRadio: Boolean by booleanValue(KEY_CONFIGURE_RADIO, false)
}
