//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Settings for the offline mesh transport (FeatureFlags.meshTransport):
//! the on/off toggle, our contact card as a QR code, scanning someone else's
//! card, the list of mesh contacts, and link/node statistics.
//! Strings are intentionally unlocalized while the feature is flag-gated.

import SignalServiceKit
import SignalUI
import UIKit

class MeshSettingsViewController: OWSTableViewController2 {

    private var refreshTimer: Timer?

    override func viewDidLoad() {
        super.viewDidLoad()
        title = LocalizationNotNeeded("Mesh")
        updateTableContents()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(meshStatusDidChange),
            name: .meshStatusDidChange,
            object: nil,
        )
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        updateTableContents()
        refreshTimer?.invalidate()
        refreshTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            self?.updateTableContents()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        refreshTimer?.invalidate()
        refreshTimer = nil
    }

    @objc
    private func meshStatusDidChange() {
        DispatchQueue.main.async { [weak self] in
            self?.updateTableContents()
        }
    }

    // MARK: - Contents

    func updateTableContents() {
        let service = MeshNodeService.shared
        let contents = OWSTableContents()

        // Toggle
        let toggleSection = OWSTableSection(
            title: nil,
            items: [
                .switch(
                    withText: LocalizationNotNeeded("Mesh transport"),
                    isOn: { service.isEnabled },
                    actionBlock: { [weak self] cellSwitch in
                        service.setEnabled(cellSwitch.isOn)
                        self?.updateTableContents()
                    },
                ),
                .switch(
                    withText: LocalizationNotNeeded("RNode LoRa radio"),
                    subtitle: LocalizationNotNeeded("Connect to an RNode board over Bluetooth"),
                    isOn: { service.isRNodeEnabled },
                    isEnabled: { service.isEnabled },
                    actionBlock: { [weak self] cellSwitch in
                        service.setRNodeEnabled(cellSwitch.isOn)
                        self?.updateTableContents()
                    },
                ),
            ],
            footerTitle: LocalizationNotNeeded(
                "Carries texts to nearby Asher users over Bluetooth, and further through phones and LoRa radios that relay them, when there is no internet. Attachments are not carried."
            ),
        )
        contents.add(toggleSection)

        // My card + contacts (one read)
        let databaseStorage = SSKEnvironment.shared.databaseStorageRef
        let (cardBase64, fingerprint, contacts): (String?, Data?, [MeshContactRecord]) = databaseStorage.read { tx in
            (
                MeshIdentityManager.shared.cardBase64(tx: tx),
                MeshIdentityManager.shared.fingerprint(tx: tx),
                MeshContactStore.shared.all(tx: tx)
            )
        }

        let cardSection = OWSTableSection(title: LocalizationNotNeeded("My card"))
        if let cardBase64 {
            cardSection.add(OWSTableItem(customCellBlock: {
                Self.qrCell(base64: cardBase64)
            }))
            cardSection.add(.copyableItem(
                label: LocalizationNotNeeded("Card"),
                value: String(cardBase64.prefix(20)) + "\u{2026}",
                pasteboardValue: cardBase64,
            ))
        } else {
            cardSection.add(.label(
                withText: LocalizationNotNeeded("Turn on the mesh transport to create your card."),
                accessoryType: .none,
            ))
        }
        if let fingerprint {
            cardSection.add(.copyableItem(
                label: LocalizationNotNeeded("Fingerprint"),
                value: fingerprint.meshHex,
            ))
        }
        contents.add(cardSection)

        let contactsSection = OWSTableSection(title: LocalizationNotNeeded("Mesh contacts"))
        contactsSection.add(.actionItem(
            withText: LocalizationNotNeeded("Scan a contact card"),
            actionBlock: { [weak self] in
                self?.presentScanner()
            },
        ))
        for record in contacts {
            contactsSection.add(.copyableItem(
                label: record.name,
                subtitle: record.fingerprint.meshHex,
                value: nil,
                pasteboardValue: record.fingerprint.meshHex,
            ))
        }
        contents.add(contactsSection)

        // Links & stats
        let status = service.status
        let linksSection = OWSTableSection(title: LocalizationNotNeeded("Links"))
        linksSection.add(.label(
            withText: LocalizationNotNeeded("Node"),
            accessoryText: LocalizationNotNeeded(status.isRunning ? "Running" : "Stopped"),
            accessoryType: .none,
        ))
        linksSection.add(.label(
            withText: LocalizationNotNeeded("Attached links"),
            accessoryText: "\(status.attachedLinks)",
            accessoryType: .none,
        ))
        linksSection.add(.label(
            withText: LocalizationNotNeeded("Neighbours"),
            accessoryText: "\(status.neighbours.count)",
            accessoryType: .none,
        ))
        for (link, neighbour) in status.neighbours.sorted(by: { $0.key < $1.key }) {
            linksSection.add(.label(
                withText: LocalizationNotNeeded("Link \(link)"),
                accessoryText: String(neighbour.meshHex.prefix(16)),
                accessoryType: .none,
            ))
        }
        if let stats = service.stats() {
            let rows: [(String, UInt64)] = [
                ("Frames in", stats.framesIn),
                ("Frames dropped (rate)", stats.framesDroppedRate),
                ("Frames dropped (invalid)", stats.framesDroppedInvalid),
                ("Bundles in", stats.bundlesIn),
                ("Bundles forwarded", stats.bundlesForwarded),
                ("Bundles dropped (quota)", stats.bundlesDroppedQuota),
                ("Messages delivered", stats.messagesDelivered),
                ("Messages deferred", stats.messagesDeferred),
                ("Messages undecryptable", stats.messagesUndecryptable),
                ("Acks verified", stats.acksVerified),
                ("Acks rejected", stats.acksRejected),
                ("Bytes out", stats.bytesOut),
                ("Carry store bundles", stats.storeBundles),
                ("Carry store bytes", stats.storeBytes),
                ("Outstanding", stats.outstanding),
            ]
            for (label, value) in rows {
                linksSection.add(.label(
                    withText: LocalizationNotNeeded(label),
                    accessoryText: "\(value)",
                    accessoryType: .none,
                ))
            }
        }
        contents.add(linksSection)

        self.contents = contents
    }

    private static func qrCell(base64: String) -> UITableViewCell {
        let cell = OWSTableItem.newCell()
        cell.selectionStyle = .none

        let qrView = QRCodeView()
        // The card is URL-safe base64 text; encode it as bytes so any scanner
        // (ours, Android's, a desktop webcam) gets the string back verbatim.
        if let image = QRCodeGenerator().generateUnstyledQRCode(data: Data(base64.utf8)) {
            qrView.setQRCode(image: image)
        } else {
            qrView.setError()
        }

        cell.contentView.addSubview(qrView)
        qrView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            qrView.topAnchor.constraint(equalTo: cell.contentView.topAnchor, constant: 16),
            qrView.bottomAnchor.constraint(equalTo: cell.contentView.bottomAnchor, constant: -16),
            qrView.centerXAnchor.constraint(equalTo: cell.contentView.centerXAnchor),
            qrView.widthAnchor.constraint(equalToConstant: 240),
            qrView.heightAnchor.constraint(equalTo: qrView.widthAnchor),
        ])
        return cell
    }

    // MARK: - Scanning

    private func presentScanner() {
        let vc = MeshScanCardViewController { [weak self] card in
            do {
                try MeshNodeService.shared.addContact(card: card)
                self?.updateTableContents()
            } catch {
                OWSActionSheets.showErrorAlert(message: LocalizationNotNeeded("Could not add this card: \(error)"))
            }
        }
        navigationController?.pushViewController(vc, animated: true)
    }
}

// MARK: - Scanner

/// Hosts the shared QR scanner and turns a scanned payload into a
/// `MeshContactCard` (URL-safe base64, as printed by "My card").
class MeshScanCardViewController: OWSViewController, QRCodeScanDelegate {

    private let onScan: (MeshContactCard) -> Void
    private let qrCodeScanViewController = QRCodeScanViewController(appearance: .framed)

    init(onScan: @escaping (MeshContactCard) -> Void) {
        self.onScan = onScan
        super.init()
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        title = LocalizationNotNeeded("Scan a mesh card")
        view.backgroundColor = .Signal.background

        qrCodeScanViewController.delegate = self
        addChild(qrCodeScanViewController)
        let qrView = qrCodeScanViewController.view!
        view.addSubview(qrView)

        let instructionsLabel = UILabel()
        instructionsLabel.text = LocalizationNotNeeded("Point the camera at the other person's mesh card (Settings > Mesh > My card).")
        instructionsLabel.font = .dynamicTypeBody
        instructionsLabel.textColor = .Signal.label
        instructionsLabel.textAlignment = .center
        instructionsLabel.numberOfLines = 0
        view.addSubview(instructionsLabel)

        qrView.translatesAutoresizingMaskIntoConstraints = false
        instructionsLabel.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            qrView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            qrView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            qrView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            qrView.heightAnchor.constraint(equalTo: qrView.widthAnchor),

            instructionsLabel.topAnchor.constraint(equalTo: qrView.bottomAnchor, constant: 16),
            instructionsLabel.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            instructionsLabel.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
        ])
    }

    // MARK: QRCodeScanDelegate

    func qrCodeScanViewDismiss(_ qrCodeScanViewController: QRCodeScanViewController) {
        navigationController?.popViewController(animated: true)
    }

    func qrCodeScanViewScanned(
        qrCodeData: Data?,
        qrCodeString: String?,
    ) -> QRCodeScanOutcome {
        var candidates: [String] = []
        if let qrCodeString {
            candidates.append(qrCodeString)
        }
        if let qrCodeData, let fromData = String(data: qrCodeData, encoding: .utf8) {
            candidates.append(fromData)
        }
        for candidate in candidates {
            let trimmed = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
            if let card = try? MeshContactCard(base64: trimmed) {
                onScan(card)
                navigationController?.popViewController(animated: true)
                return .stopScanning
            }
        }
        OWSActionSheets.showErrorAlert(message: LocalizationNotNeeded("That QR code is not a mesh contact card."))
        return .continueScanning
    }
}
