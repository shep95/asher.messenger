/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh.ui

import android.net.Uri
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
import org.thoughtcrime.securesms.mesh.jni.MeshIdentity
import org.thoughtcrime.securesms.mesh.jni.MeshNode
import org.thoughtcrime.securesms.recipients.RecipientId
import java.io.IOException

class MeshSettingsViewModel : ViewModel() {

  companion object {
    private val TAG = Log.tag(MeshSettingsViewModel::class.java)

    /** `MeshNode_SelfTest` budget. */
    private const val SELF_TEST_TIMEOUT_MS = 15_000

    /** How long a restore waits for the node after switching the mesh on. */
    private const val NODE_START_TIMEOUT_MS = 15_000L

    /** Refuse to read a backup file larger than this (the whole snapshot is a few MiB at most). */
    private const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
  }

  data class ContactRow(val fingerprint: ByteArray, val name: String, val fingerprintText: String, val recipientId: RecipientId?)

  /** One `MeshNode_Nearby` entry. [isContact] when we already hold it as a saved mesh contact. */
  data class NearbyRow(val fingerprint: ByteArray, val name: String, val fingerprintText: String, val lastSeenSecs: Long, val direct: Boolean, val isContact: Boolean)

  enum class PassphrasePurpose { EXPORT, RESTORE }

  /** A passphrase dialog the screen should show; [uri] is the picked file for a restore. */
  data class PassphrasePrompt(val purpose: PassphrasePurpose, val uri: Uri? = null)

  data class State(
    val enabled: Boolean = MeshTransport.isEnabled(),
    val bleEnabled: Boolean = SignalStore.mesh.bleEnabled,
    val usbEnabled: Boolean = SignalStore.mesh.usbEnabled,
    val lanEnabled: Boolean = SignalStore.mesh.lanEnabled,
    val configureRadio: Boolean = SignalStore.mesh.configureRadio,
    val cardBase64: String? = null,
    val fingerprintText: String? = null,
    val contacts: List<ContactRow> = emptyList(),
    val nearby: List<NearbyRow> = emptyList(),
    val selfTestRunning: Boolean = false,
    val selfTestReport: String? = null,
    val passphrasePrompt: PassphrasePrompt? = null,
    /** A long-running operation the screen should show a progress dialog for. */
    val busy: String? = null,
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
      val records = MeshContacts.all()
      val contacts = records.map { record ->
        ContactRow(
          fingerprint = record.fingerprint,
          name = record.name ?: "Mesh ${MeshContacts.hex(record.fingerprint).take(8)}",
          fingerprintText = MeshContacts.displayHex(record.fingerprint),
          recipientId = record.recipientId
        )
      }
      val known = records.map { MeshContacts.hex(it.fingerprint) }.toHashSet()
      val nearby = MeshTransport.node?.let { node ->
        try {
          node.nearby().map { entry ->
            val hex = MeshContacts.hex(entry.fingerprint)
            NearbyRow(
              fingerprint = entry.fingerprint,
              name = entry.name.trim().ifEmpty { "Mesh ${hex.take(8)}" },
              fingerprintText = MeshContacts.displayHex(entry.fingerprint),
              lastSeenSecs = entry.lastSeenSecs,
              direct = entry.direct,
              isContact = hex in known
            )
          }
        } catch (e: Exception) {
          Log.w(TAG, "Nearby list unavailable", e)
          emptyList()
        }
      } ?: emptyList()

      _state.update {
        it.copy(
          enabled = MeshTransport.isEnabled(),
          bleEnabled = SignalStore.mesh.bleEnabled,
          usbEnabled = SignalStore.mesh.usbEnabled,
          lanEnabled = SignalStore.mesh.lanEnabled,
          configureRadio = SignalStore.mesh.configureRadio,
          cardBase64 = card?.toBase64(),
          fingerprintText = card?.fingerprint?.let(MeshContacts::displayHex),
          contacts = contacts,
          nearby = nearby
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

  fun setLanEnabled(enabled: Boolean) {
    SignalStore.mesh.lanEnabled = enabled
    _state.update { it.copy(lanEnabled = enabled, message = restartHint()) }
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

  // ---- nearby ----------------------------------------------------------------

  /** Saves a nearby node as a contact from the card the node already holds (`MeshNode_Contact`). */
  fun addNearby(row: NearbyRow) {
    viewModelScope.launch(Dispatchers.IO) {
      val node = MeshTransport.node
      if (node == null) {
        _state.update { it.copy(message = "Mesh is not running") }
        return@launch
      }
      try {
        val bytes = node.contact(row.fingerprint) ?: throw IllegalStateException("The node no longer holds a card for ${row.fingerprintText}")
        val card = MeshContactCard.decode(bytes)
        MeshContacts.upsert(card)
        // Re-adding pins the card as an explicit contact so it survives the learned-contact eviction.
        runCatching { node.addContact(bytes) }
        _state.update { it.copy(message = "Added ${row.name}") }
      } catch (e: Exception) {
        Log.w(TAG, "Could not add nearby node ${row.fingerprintText}", e)
        _state.update { it.copy(message = e.message ?: "Could not add contact") }
      }
      refresh()
    }
  }

  // ---- self-test -------------------------------------------------------------

  fun runSelfTest() {
    if (_state.value.selfTestRunning) return
    val node = MeshTransport.node
    if (node == null) {
      _state.update { it.copy(message = "Turn the mesh on first") }
      return
    }
    _state.update { it.copy(selfTestRunning = true) }
    viewModelScope.launch(Dispatchers.IO) {
      val report = try {
        node.selfTest(SELF_TEST_TIMEOUT_MS)
      } catch (e: Exception) {
        Log.w(TAG, "Self-test threw", e)
        "FAIL self-test could not run: ${e.message ?: e.javaClass.simpleName}"
      }
      _state.update { it.copy(selfTestRunning = false, selfTestReport = report) }
    }
  }

  fun dismissSelfTest() {
    _state.update { it.copy(selfTestReport = null) }
  }

  // ---- backup ----------------------------------------------------------------

  fun promptPassphrase(purpose: PassphrasePurpose, uri: Uri? = null) {
    _state.update { it.copy(passphrasePrompt = PassphrasePrompt(purpose, uri)) }
  }

  fun dismissPassphrase() {
    _state.update { it.copy(passphrasePrompt = null) }
  }

  /** `MeshNode_ExportBackup` written to the document the user picked. */
  fun exportBackup(passphrase: String, uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      val node = MeshTransport.node
      if (node == null) {
        _state.update { it.copy(message = "Turn the mesh on first") }
        return@launch
      }
      _state.update { it.copy(busy = "Exporting mesh backup…") }
      try {
        val blob = node.exportBackup(passphrase)
        val resolver = AppDependencies.application.contentResolver
        val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("Could not open the destination")
        out.use { it.write(blob) }
        _state.update { it.copy(busy = null, message = "Mesh backup saved (${blob.size / 1024} KiB)") }
      } catch (e: Exception) {
        Log.w(TAG, "Backup export failed", e)
        _state.update { it.copy(busy = null, message = "Backup failed: ${e.message ?: e.javaClass.simpleName}") }
      }
    }
  }

  /**
   * Restores an "ASHB" file. With no stored identity yet, `MeshIdentity_FromBackup` recovers it
   * first (it must be this account's identity key, since the mesh identity is the ACI identity);
   * then the running node (started if needed) merges the snapshot with `MeshNode_ImportBackup`.
   */
  fun restoreBackup(passphrase: String, uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      _state.update { it.copy(busy = "Restoring mesh backup…") }
      try {
        val blob = readDocument(uri)

        if (SignalStore.mesh.identityExport == null) {
          val identity = MeshIdentity.fromBackup(passphrase, blob)
          val accountKey = SignalStore.account.aciIdentityKey.publicKey
          if (identity.card().identityKey != accountKey) {
            throw IllegalStateException("This backup was made by a different identity")
          }
          SignalStore.mesh.identityExport = identity.export()
          MeshIdentityManager.reset()
          Log.i(TAG, "Recovered mesh identity ${MeshContacts.hex(identity.fingerprint)} from backup")
        }

        val node = ensureNodeRunning() ?: throw IllegalStateException("The mesh did not start; turn it on and try again")
        node.importBackup(passphrase, blob)
        syncContactsFromNode(node)
        _state.update { it.copy(busy = null, message = "Mesh backup restored") }
      } catch (e: Exception) {
        Log.w(TAG, "Backup restore failed", e)
        _state.update { it.copy(busy = null, message = "Restore failed: ${e.message ?: e.javaClass.simpleName}") }
      }
      refresh()
    }
  }

  private fun readDocument(uri: Uri): ByteArray {
    val resolver = AppDependencies.application.contentResolver
    val input = resolver.openInputStream(uri) ?: throw IOException("Could not open the file")
    input.use { stream ->
      val bytes = stream.readBytes()
      if (bytes.size > MAX_BACKUP_BYTES) throw IOException("File too large to be a mesh backup")
      return bytes
    }
  }

  private fun ensureNodeRunning(): MeshNode? {
    MeshTransport.node?.let { return it }
    if (!MeshTransport.isAvailable()) return null
    MeshTransport.setEnabled(AppDependencies.application, true)
    _state.update { it.copy(enabled = MeshTransport.isEnabled()) }
    return MeshRuntime.awaitNode(NODE_START_TIMEOUT_MS)
  }

  /** After an import the node holds the merged contacts; mirror them into the app's table and recipients. */
  private fun syncContactsFromNode(node: MeshNode) {
    for (bytes in node.contacts()) {
      try {
        MeshContacts.upsert(MeshContactCard.decode(bytes))
      } catch (e: Exception) {
        Log.w(TAG, "A restored card did not decode", e)
      }
    }
  }

  fun clearMessage() {
    _state.update { it.copy(message = null) }
  }

  private fun restartHint(): String? = if (MeshRuntime.isRunning) "Turn the mesh off and on to apply" else null
}
