/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.ComposeFragment
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.QrCode
import org.signal.core.ui.compose.QrCodeData
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.Texts
import org.signal.core.ui.compose.horizontalGutters
import org.signal.core.ui.permissions.Permissions
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.mesh.MeshContacts
import org.thoughtcrime.securesms.mesh.MeshStatus
import org.thoughtcrime.securesms.mesh.MeshTransport
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.CommunicationActions
import org.thoughtcrime.securesms.util.navigation.safeNavigate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings > Offline mesh: the switch and per-link toggles (Bluetooth, USB radio, Wi-Fi LAN), our
 * contact card as a QR code, scanning a contact's card, the saved mesh contacts, the nodes seen
 * nearby with an Add button, the loopback self-test, encrypted backup export/restore, and the
 * live links and counters. Shown only while `mesh.transport` is on.
 */
class MeshSettingsFragment : ComposeFragment() {

  companion object {
    private const val BACKUP_MIME = "application/octet-stream"
    private const val BACKUP_EXTENSION = ".asherbackup"
  }

  private val viewModel: MeshSettingsViewModel by viewModels()

  /** The passphrase typed for an export, held until the SAF picker returns the destination. */
  private var pendingExportPassphrase: String? = null

  private val createBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri: Uri? ->
    val passphrase = pendingExportPassphrase
    pendingExportPassphrase = null
    if (uri != null && passphrase != null) {
      viewModel.exportBackup(passphrase, uri)
    }
  }

  private val openBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
    if (uri != null) {
      viewModel.promptPassphrase(MeshSettingsViewModel.PassphrasePurpose.RESTORE, uri)
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.refresh()
    setFragmentResultListener(MeshScanFragment.RESULT_KEY) { _, bundle ->
      val text = bundle.getString(MeshScanFragment.RESULT_CARD) ?: return@setFragmentResultListener
      if (viewModel.addCard(text)) {
        Toast.makeText(requireContext(), R.string.MeshSettings__contact_added, Toast.LENGTH_SHORT).show()
      } else {
        Toast.makeText(requireContext(), R.string.MeshSettings__not_a_card, Toast.LENGTH_LONG).show()
      }
    }
  }

  @Deprecated("Deprecated in Java")
  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
  }

  private fun onToggle(enabled: Boolean) {
    if (!enabled) {
      viewModel.setEnabled(false)
      return
    }
    Permissions.with(this)
      .request(*MeshTransport.blePermissions())
      .ifNecessary()
      .withRationaleDialog(getString(R.string.MeshSettings__bluetooth_rationale), R.drawable.symbol_link_24)
      .onAnyResult { viewModel.setEnabled(true) }
      .execute()
  }

  private fun onPassphraseConfirmed(prompt: MeshSettingsViewModel.PassphrasePrompt, passphrase: String) {
    viewModel.dismissPassphrase()
    when (prompt.purpose) {
      MeshSettingsViewModel.PassphrasePurpose.EXPORT -> {
        pendingExportPassphrase = passphrase
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        createBackupLauncher.launch("asher-mesh-$stamp$BACKUP_EXTENSION")
      }
      MeshSettingsViewModel.PassphrasePurpose.RESTORE -> {
        val uri = prompt.uri ?: return
        viewModel.restoreBackup(passphrase, uri)
      }
    }
  }

  @Composable
  override fun FragmentContent() {
    val state by viewModel.state.collectAsState()
    val mesh by viewModel.mesh.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.message) {
      state.message?.let {
        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        viewModel.clearMessage()
      }
    }

    MeshSettingsContent(
      state = state,
      mesh = mesh,
      onNavigationClick = { findNavController().popBackStack() },
      onToggle = ::onToggle,
      onBleToggle = viewModel::setBleEnabled,
      onUsbToggle = viewModel::setUsbEnabled,
      onLanToggle = viewModel::setLanEnabled,
      onConfigureRadioToggle = viewModel::setConfigureRadio,
      onBroadcast = viewModel::broadcastCard,
      onScan = { findNavController().safeNavigate(R.id.action_meshSettingsFragment_to_meshScanFragment) },
      onOpenContact = { row ->
        row.recipientId?.let { CommunicationActions.startConversation(requireContext(), Recipient.resolved(it), null) }
      },
      onRemoveContact = { row -> viewModel.removeContact(row.fingerprint) },
      onAddNearby = viewModel::addNearby,
      onSelfTest = viewModel::runSelfTest,
      onExportBackup = { viewModel.promptPassphrase(MeshSettingsViewModel.PassphrasePurpose.EXPORT) },
      onRestoreBackup = { openBackupLauncher.launch(arrayOf("*/*")) }
    )

    state.selfTestReport?.let { report ->
      SelfTestDialog(report = report, onDismiss = viewModel::dismissSelfTest)
    }

    state.passphrasePrompt?.let { prompt ->
      PassphraseDialog(
        title = stringResource(
          if (prompt.purpose == MeshSettingsViewModel.PassphrasePurpose.EXPORT) R.string.MeshSettings__export_backup_title else R.string.MeshSettings__restore_backup_title
        ),
        onConfirm = { passphrase -> onPassphraseConfirmed(prompt, passphrase) },
        onDismiss = viewModel::dismissPassphrase
      )
    }

    state.busy?.let { message ->
      Dialogs.IndeterminateProgressDialog(message)
    }
  }
}

@Composable
private fun MeshSettingsContent(
  state: MeshSettingsViewModel.State,
  mesh: MeshStatus.Snapshot,
  onNavigationClick: () -> Unit,
  onToggle: (Boolean) -> Unit,
  onBleToggle: (Boolean) -> Unit,
  onUsbToggle: (Boolean) -> Unit,
  onLanToggle: (Boolean) -> Unit,
  onConfigureRadioToggle: (Boolean) -> Unit,
  onBroadcast: () -> Unit,
  onScan: () -> Unit,
  onOpenContact: (MeshSettingsViewModel.ContactRow) -> Unit,
  onRemoveContact: (MeshSettingsViewModel.ContactRow) -> Unit,
  onAddNearby: (MeshSettingsViewModel.NearbyRow) -> Unit,
  onSelfTest: () -> Unit,
  onExportBackup: () -> Unit,
  onRestoreBackup: () -> Unit
) {
  Scaffolds.Settings(
    title = stringResource(R.string.MeshSettings__title),
    navigationContentDescription = stringResource(R.string.MeshSettings__navigate_up),
    navigationIcon = SignalIcons.ArrowStart.imageVector,
    onNavigationClick = onNavigationClick
  ) { contentPadding ->
    LazyColumn(contentPadding = contentPadding) {
      item {
        Rows.ToggleRow(
          checked = state.enabled,
          text = stringResource(R.string.MeshSettings__enable),
          label = stringResource(R.string.MeshSettings__enable_summary),
          onCheckChanged = onToggle
        )
      }

      item {
        Rows.ToggleRow(
          checked = state.lanEnabled,
          text = stringResource(R.string.MeshSettings__lan),
          label = stringResource(R.string.MeshSettings__lan_summary),
          onCheckChanged = onLanToggle
        )
      }

      item {
        Rows.ToggleRow(
          checked = state.bleEnabled,
          text = stringResource(R.string.MeshSettings__ble),
          label = stringResource(R.string.MeshSettings__ble_summary),
          onCheckChanged = onBleToggle
        )
      }

      item {
        Rows.ToggleRow(
          checked = state.usbEnabled,
          text = stringResource(R.string.MeshSettings__usb),
          label = stringResource(R.string.MeshSettings__usb_summary),
          onCheckChanged = onUsbToggle
        )
      }

      item {
        Rows.ToggleRow(
          checked = state.configureRadio,
          text = stringResource(R.string.MeshSettings__configure_radio),
          label = stringResource(R.string.MeshSettings__configure_radio_summary),
          onCheckChanged = onConfigureRadioToggle,
          enabled = state.usbEnabled
        )
      }

      item { Dividers.Default() }

      item { Texts.SectionHeader(text = stringResource(R.string.MeshSettings__my_card)) }

      item {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalGutters()
            .padding(vertical = 8.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          val card = state.cardBase64
          if (card != null) {
            QrCode(
              data = QrCodeData.forData(card, supportIconOverlay = false),
              modifier = Modifier.size(240.dp),
              foregroundColor = MaterialTheme.colorScheme.onSurface,
              backgroundColor = MaterialTheme.colorScheme.surface
            )
            Text(
              text = state.fingerprintText ?: "",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          } else {
            Text(text = stringResource(R.string.MeshSettings__card_unavailable), style = MaterialTheme.typography.bodyMedium)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Buttons.LargeTonal(onClick = onScan) {
              Text(text = stringResource(R.string.MeshSettings__scan_card))
            }
            Buttons.LargeTonal(onClick = onBroadcast, enabled = mesh.running) {
              Text(text = stringResource(R.string.MeshSettings__broadcast_card))
            }
          }
        }
      }

      item { Dividers.Default() }

      item { Texts.SectionHeader(text = stringResource(R.string.MeshSettings__nearby)) }

      if (state.nearby.isEmpty()) {
        item {
          Text(
            text = stringResource(if (mesh.running) R.string.MeshSettings__no_nearby else R.string.MeshSettings__nearby_needs_mesh),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
              .horizontalGutters()
              .padding(vertical = 8.dp)
          )
        }
      }

      items(state.nearby, key = { "nearby-" + it.fingerprintText }) { row ->
        NearbyRow(row = row, onAdd = { onAddNearby(row) })
      }

      item { Dividers.Default() }

      item { Texts.SectionHeader(text = stringResource(R.string.MeshSettings__contacts)) }

      if (state.contacts.isEmpty()) {
        item {
          Text(
            text = stringResource(R.string.MeshSettings__no_contacts),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
              .horizontalGutters()
              .padding(vertical = 8.dp)
          )
        }
      }

      items(state.contacts, key = { it.fingerprintText }) { row ->
        Rows.TextRow(
          text = row.name,
          label = row.fingerprintText,
          icon = painterResource(R.drawable.symbol_link_24),
          onClick = { onOpenContact(row) },
          onLongClick = { onRemoveContact(row) }
        )
      }

      item { Dividers.Default() }

      item { Texts.SectionHeader(text = stringResource(R.string.MeshSettings__backup)) }

      item {
        Rows.TextRow(
          text = stringResource(R.string.MeshSettings__export_backup),
          label = stringResource(R.string.MeshSettings__export_backup_summary),
          onClick = onExportBackup,
          enabled = mesh.running
        )
      }

      item {
        Rows.TextRow(
          text = stringResource(R.string.MeshSettings__restore_backup),
          label = stringResource(R.string.MeshSettings__restore_backup_summary),
          onClick = onRestoreBackup
        )
      }

      item { Dividers.Default() }

      item { Texts.SectionHeader(text = stringResource(R.string.MeshSettings__links)) }

      item {
        val running = if (mesh.running) stringResource(R.string.MeshSettings__running) else stringResource(R.string.MeshSettings__stopped)
        Rows.TextRow(text = running, label = mesh.lastError)
      }

      item {
        Rows.TextRow(
          text = stringResource(R.string.MeshSettings__self_test),
          label = stringResource(if (state.selfTestRunning) R.string.MeshSettings__self_test_running else R.string.MeshSettings__self_test_summary),
          onClick = onSelfTest,
          enabled = mesh.running && !state.selfTestRunning
        )
      }

      if (mesh.links.isEmpty()) {
        item {
          Text(
            text = stringResource(R.string.MeshSettings__no_links),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
              .horizontalGutters()
              .padding(vertical = 8.dp)
          )
        }
      }

      items(mesh.links.values.toList(), key = { it.id }) { link ->
        val neighbour = mesh.neighbours[link.id]?.let { MeshContacts.displayHex(it) }
        Rows.TextRow(
          text = "${link.kind.name.lowercase().replace('_', ' ')} · ${link.label}",
          label = if (neighbour != null) "MTU ${link.mtu} · neighbour $neighbour" else "MTU ${link.mtu}"
        )
      }

      mesh.stats?.let { s ->
        item {
          Text(
            text = stringResource(
              R.string.MeshSettings__stats,
              s.framesIn,
              s.bundlesIn,
              s.bundlesForwarded,
              s.messagesDelivered,
              s.outstanding,
              s.storeBundles,
              s.storeBytes / 1024
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
              .horizontalGutters()
              .padding(vertical = 8.dp)
          )
        }
      }
    }
  }
}

/** Name, fingerprint and last-seen on the left; a "direct" badge and an Add button (or "added") on the right. */
@Composable
private fun NearbyRow(row: MeshSettingsViewModel.NearbyRow, onAdd: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .horizontalGutters()
      .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = row.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        if (row.direct) {
          Text(
            text = stringResource(R.string.MeshSettings__nearby_direct),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
          )
        }
      }
      Text(
        text = row.fingerprintText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Text(
        text = stringResource(R.string.MeshSettings__nearby_seen, lastSeenText(row.lastSeenSecs)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    if (row.isContact) {
      Text(
        text = stringResource(R.string.MeshSettings__nearby_added),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    } else {
      Buttons.Small(onClick = onAdd) {
        Text(text = stringResource(R.string.MeshSettings__nearby_add))
      }
    }
  }
}

private fun lastSeenText(lastSeenSecs: Long): String {
  val age = (System.currentTimeMillis() / 1000 - lastSeenSecs).coerceAtLeast(0)
  return when {
    age < 60 -> "${age}s"
    age < 3600 -> "${age / 60}m"
    else -> "${age / 3600}h"
  }
}

/** The multi-line `MeshNode_SelfTest` report in a scrollable monospace dialog. */
@Composable
private fun SelfTestDialog(report: String, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(text = stringResource(R.string.MeshSettings__self_test_title)) },
    text = {
      Column(
        modifier = Modifier
          .heightIn(max = 400.dp)
          .verticalScroll(rememberScrollState())
      ) {
        Text(text = report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(text = stringResource(android.R.string.ok))
      }
    }
  )
}

/** Asks for the backup passphrase; the confirm button stays disabled until something is typed. */
@Composable
private fun PassphraseDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
  var passphrase by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(text = title) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.MeshSettings__passphrase_hint), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
          value = passphrase,
          onValueChange = { passphrase = it },
          label = { Text(text = stringResource(R.string.MeshSettings__passphrase)) },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          modifier = Modifier.fillMaxWidth()
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(passphrase) }, enabled = passphrase.isNotEmpty()) {
        Text(text = stringResource(android.R.string.ok))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(text = stringResource(android.R.string.cancel))
      }
    }
  )
}
