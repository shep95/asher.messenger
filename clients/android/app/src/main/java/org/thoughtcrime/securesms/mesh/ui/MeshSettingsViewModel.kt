/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mesh.MeshContacts
import org.thoughtcrime.securesms.mesh.MeshIdentityManager
import org.thoughtcrime.securesms.mesh.MeshRuntime
import org.thoughtcrime.securesms.mesh.MeshStatus
import org.thoughtcrime.securesms.mesh.MeshTransport
import org.thoughtcrime.securesms.mesh.jni.MeshContactCard
import org.thoughtcrime.securesms.recipients.RecipientId

class MeshSettingsViewModel : ViewModel() {

  companion object {
    private val TAG = Log.tag(MeshSettingsViewModel::class.java)
  }

  data class ContactRow(val fingerprint: ByteArray, val name: String, val fingerprintText: String, val recipientId: RecipientId?)

  data class State(
    val enabled: Boolean = MeshTransport.isEnabled(),
    val bleEnabled: Boolean = SignalStore.mesh.bleEnabled,
    val usbEnabled: Boolean = SignalStore.mesh.usbEnabled,
    val configureRadio: Boolean = SignalStore.mesh.configureRadio,
    val cardBase64: String? = null,
    val fingerprintText: String? = null,
    val contacts: List<ContactRow> = emptyList(),
    val message: String? = null
  )

  private val _state = MutableStateFlow(State())
  val state: StateFlow<State> = _state

  val mesh: StateFlow<MeshStatus.Snapshot> = MeshStatus.state

  init {
    refresh()
  }

  fun refresh() {
    viewModelScope.launch(Dispatchers.IO) {
      val card = try {
        MeshIdentityManager.card()
      } catch (e: Exception) {
        Log.w(TAG, "Could not build the mesh identity", e)
        null
      }
      val contacts = MeshContacts.all().map { record ->
        ContactRow(
          fingerprint = record.fingerprint,
          name = record.name ?: "Mesh ${MeshContacts.hex(record.fingerprint).take(8)}",
          fingerprintText = MeshContacts.displayHex(record.fingerprint),
          recipientId = record.recipientId
        )
      }
      _state.update {
        it.copy(
          enabled = MeshTransport.isEnabled(),
          bleEnabled = SignalStore.mesh.bleEnabled,
          usbEnabled = SignalStore.mesh.usbEnabled,
          configureRadio = SignalStore.mesh.configureRadio,
          cardBase64 = card?.toBase64(),
          fingerprintText = card?.fingerprint?.let(MeshContacts::displayHex),
          contacts = contacts
        )
      }
    }
  }

  fun setEnabled(enabled: Boolean) {
    MeshTransport.setEnabled(AppDependencies.application, enabled)
    _state.update { it.copy(enabled = MeshTransport.isEnabled()) }
  }

  fun setBleEnabled(enabled: Boolean) {
    SignalStore.mesh.bleEnabled = enabled
    _state.update { it.copy(bleEnabled = enabled, message = restartHint()) }
  }

  fun setUsbEnabled(enabled: Boolean) {
    SignalStore.mesh.usbEnabled = enabled
    _state.update { it.copy(usbEnabled = enabled, message = restartHint()) }
  }

  fun setConfigureRadio(enabled: Boolean) {
    SignalStore.mesh.configureRadio = enabled
    _state.update { it.copy(configureRadio = enabled) }
  }

  fun broadcastCard() {
    viewModelScope.launch(Dispatchers.IO) {
      val ok = MeshRuntime.broadcastCard()
      _state.update { it.copy(message = if (ok) "Card broadcast to nearby nodes" else "Mesh is not running") }
    }
  }

  /** Adds a card scanned from a QR code. Returns false if it did not decode. */
  fun addCard(base64: String): Boolean {
    val card = try {
      MeshContactCard.fromBase64(base64.trim())
    } catch (e: Exception) {
      Log.w(TAG, "Scanned text is not a mesh contact card", e)
      return false
    }
    viewModelScope.launch(Dispatchers.IO) {
      MeshContacts.upsert(card)
      MeshTransport.node?.let { node -> runCatching { node.addContact(card.encode()) } }
      refresh()
    }
    return true
  }

  fun removeContact(fingerprint: ByteArray) {
    viewModelScope.launch(Dispatchers.IO) {
      MeshContacts.remove(fingerprint)
      refresh()
    }
  }

  fun clearMessage() {
    _state.update { it.copy(message = null) }
  }

  private fun restartHint(): String? = if (MeshRuntime.isRunning) "Turn the mesh off and on to apply" else null
}
