/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.ComposeFragment
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

/**
 * Settings > Offline mesh: the switch, our contact card as a QR code, scanning a contact's card,
 * the mesh contacts, and the live links and counters. Shown only while `mesh.transport` is on.
 */
class MeshSettingsFragment : ComposeFragment() {

  private val viewModel: MeshSettingsViewModel by viewModels()

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
      onConfigureRadioToggle = viewModel::setConfigureRadio,
      onBroadcast = viewModel::broadcastCard,
      onScan = { findNavController().safeNavigate(R.id.action_meshSettingsFragment_to_meshScanFragment) },
      onOpenContact = { row ->
        row.recipientId?.let { CommunicationActions.startConversation(requireContext(), Recipient.resolved(it), null) }
      },
      onRemoveContact = { row -> viewModel.removeContact(row.fingerprint) }
    )
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
  onConfigureRadioToggle: (Boolean) -> Unit,
  onBroadcast: () -> Unit,
  onScan: () -> Unit,
  onOpenContact: (MeshSettingsViewModel.ContactRow) -> Unit,
  onRemoveContact: (MeshSettingsViewModel.ContactRow) -> Unit
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

      item { Texts.SectionHeader(text = stringResource(R.string.MeshSettings__links)) }

      item {
        val running = if (mesh.running) stringResource(R.string.MeshSettings__running) else stringResource(R.string.MeshSettings__stopped)
        Rows.TextRow(text = running, label = mesh.lastError)
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
