/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh.ui

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.navigation.fragment.findNavController
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import org.signal.core.ui.permissions.Permissions
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.qr.QrScannerView
import org.thoughtcrime.securesms.R

/**
 * Scans another device's contact card (the QR from its mesh settings) and hands the text back to
 * [MeshSettingsFragment] through the fragment result API. Reuses the app's [QrScannerView].
 */
class MeshScanFragment : Fragment() {

  companion object {
    const val RESULT_KEY = "mesh_scan_result"
    const val RESULT_CARD = "card"
  }

  private val lifecycleDisposable = LifecycleDisposable()
  private var delivered = false

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
    return inflater.inflate(R.layout.mesh_scan_fragment, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    val scanner: QrScannerView = view.findViewById(R.id.mesh_scanner)
    lifecycleDisposable.bindTo(viewLifecycleOwner)

    Permissions.with(this)
      .request(Manifest.permission.CAMERA)
      .ifNecessary()
      .withRationaleDialog(getString(R.string.MeshSettings__camera_rationale), R.drawable.symbol_qrcode_24)
      .onAllGranted {
        scanner.start(viewLifecycleOwner)
        lifecycleDisposable += scanner.qrData
          .distinctUntilChanged()
          .observeOn(AndroidSchedulers.mainThread())
          .subscribe { data -> onCard(data) }
      }
      .onAnyDenied { findNavController().popBackStack() }
      .execute()
  }

  @Deprecated("Deprecated in Java")
  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
  }

  private fun onCard(data: String) {
    if (delivered) return
    delivered = true
    setFragmentResult(RESULT_KEY, bundleOf(RESULT_CARD to data))
    findNavController().popBackStack()
  }
}
