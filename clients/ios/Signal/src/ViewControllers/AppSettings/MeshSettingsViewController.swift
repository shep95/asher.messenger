//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Settings for the offline mesh transport (FeatureFlags.meshTransport):
//! the on/off toggles (mesh, Wi-Fi/LAN link, RNode radio), our contact card
//! as a QR code, scanning someone else's card, the list of mesh contacts,
//! peers seen nearby (v3 `MeshNode_Nearby`) with one-tap add, the loopback
//! self-test, encrypted backup export/restore, and link/node statistics.
//! Strings are intentionally unlocalized while the feature is flag-gated.

import SignalServiceKit
import SignalUI
import UIKit
import UniformTypeIdentifiers

class MeshSettingsViewController: OWSTableViewController2 {

    private var refreshTimer: Timer?
    /// Bytes of a backup the user picked, waiting for a passphrase.
    private var pendingRestoreBlob: Data?

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

        // Toggles
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
                    withText: LocalizationNotNeeded("Mesh over Wi-Fi"),
                    subtitle: LocalizationNotNeeded("Find Asher users on the same Wi-Fi or hotspot (no radio needed)"),
                    isOn: { service.isLanEnabled },
                    isEnabled: { service.isEnabled },
                    actionBlock: { [weak self] cellSwitch in
                        service.setLanEnabled(cellSwitch.isOn)
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
                "Carries texts, small attachments (up to 4 MB) and calls to nearby Asher users over Bluetooth and Wi-Fi, and further through phones and LoRa radios that relay them, when there is no internet."
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

        // Nearby (v3): every card seen on the mesh in the last 24 h.
        let knownFingerprints = Set(contacts.map(\.fingerprint))
        let nearby = service.nearby()
        let nearbySection = OWSTableSection(
            title: LocalizationNotNeeded("Nearby"),
            items: [],
            footerTitle: LocalizationNotNeeded(
                service.isRunning
                    ? "People whose card reached this phone in the last 24 hours. \"Direct\" means they are one hop away right now."
                    : "Turn on the mesh transport to see who is nearby."
            ),
        )
        if nearby.isEmpty {
            nearbySection.add(.label(
                withText: LocalizationNotNeeded(service.isRunning ? "Nobody seen yet" : "Mesh is off"),
                accessoryType: .none,
            ))
        }
        for peer in nearby {
            let isContact = knownFingerprints.contains(peer.fingerprint)
            let displayName = peer.name.isEmpty ? "Mesh \(peer.fingerprint.prefix(4).meshHex)" : peer.name
            var subtitleParts = [String(peer.fingerprint.meshHex.prefix(16)) + "\u{2026}"]
            if peer.isDirect {
                subtitleParts.append("Direct")
            }
            subtitleParts.append(Self.lastSeenText(secondsSinceEpoch: peer.lastSeenSecs))
            let subtitle = subtitleParts.joined(separator: " \u{00B7} ")
            nearbySection.add(.item(
                name: displayName,
                subtitle: LocalizationNotNeeded(subtitle),
                accessoryText: LocalizationNotNeeded(isContact ? "Added" : "Add"),
                accessibilityIdentifier: "mesh.nearby.\(peer.fingerprint.meshHex)",
                actionBlock: { [weak self] in
                    guard !isContact else { return }
                    self?.addNearby(peer)
                },
            ))
        }
        contents.add(nearbySection)

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

        // Diagnostics and backup (v3)
        let toolsSection = OWSTableSection(
            title: LocalizationNotNeeded("Tools"),
            items: [],
            footerTitle: LocalizationNotNeeded(
                "The self-test runs two nodes inside this app over an in-memory link and reports whether the mesh machinery works, without any other device. The backup holds your mesh identity, contacts and undelivered bundles, encrypted with the passphrase you choose."
            ),
        )
        toolsSection.add(.actionItem(
            withText: LocalizationNotNeeded("Run self-test"),
            actionBlock: { [weak self] in
                self?.runSelfTest()
            },
        ))
        toolsSection.add(.actionItem(
            withText: LocalizationNotNeeded("Export encrypted mesh backup"),
            actionBlock: { [weak self] in
                self?.exportBackup()
            },
        ))
        toolsSection.add(.actionItem(
            withText: LocalizationNotNeeded("Restore mesh backup"),
            actionBlock: { [weak self] in
                self?.pickBackupToRestore()
            },
        ))
        contents.add(toolsSection)

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

    private static func lastSeenText(secondsSinceEpoch: UInt64) -> String {
        let seen = Date(timeIntervalSince1970: TimeInterval(secondsSinceEpoch))
        let age = max(0, Date().timeIntervalSince(seen))
        if age < 60 {
            return "just now"
        }
        if age < 3600 {
            return "\(Int(age / 60)) min ago"
        }
        return "\(Int(age / 3600)) h ago"
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

    // MARK: - Nearby

    private func addNearby(_ peer: MeshNearbyPeer) {
        do {
            try MeshNodeService.shared.addNearbyContact(fingerprint: peer.fingerprint)
            updateTableContents()
        } catch MeshError.notMeshRecipient {
            OWSActionSheets.showErrorAlert(
                message: LocalizationNotNeeded("This phone no longer holds that person's card. Wait for their next beacon or scan their card."),
                fromViewController: self,
            )
        } catch {
            OWSActionSheets.showErrorAlert(
                message: LocalizationNotNeeded("Could not add this contact: \(error.localizedDescription)"),
                fromViewController: self,
            )
        }
    }

    // MARK: - Self-test

    private func runSelfTest() {
        guard MeshNodeService.shared.isRunning else {
            OWSActionSheets.showErrorAlert(
                message: LocalizationNotNeeded("Turn on the mesh transport first."),
                fromViewController: self,
            )
            return
        }
        ModalActivityIndicatorViewController.present(
            fromViewController: self,
            title: LocalizationNotNeeded("Running mesh self-test\u{2026}"),
            canCancel: false,
            backgroundBlock: { modal in
                let report: String
                do {
                    report = try MeshNodeService.shared.runSelfTest(timeoutMs: 15_000)
                } catch {
                    report = "FAIL self-test could not run: \(error.localizedDescription)"
                }
                DispatchQueue.main.async {
                    modal.dismiss { [weak self] in
                        self?.showReport(title: "Mesh self-test", text: report)
                    }
                }
            },
        )
    }

    private func showReport(title: String, text: String) {
        let vc = MeshReportViewController(reportTitle: title, text: text)
        navigationController?.pushViewController(vc, animated: true)
    }

    // MARK: - Backup

    /// Asks for a passphrase (twice when `confirm`), then calls `completion` with it.
    private func promptPassphrase(title: String, message: String, confirm: Bool, completion: @escaping (String) -> Void) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addTextField { field in
            field.placeholder = LocalizationNotNeeded("Passphrase")
            field.isSecureTextEntry = true
            field.autocorrectionType = .no
            field.autocapitalizationType = .none
        }
        if confirm {
            alert.addTextField { field in
                field.placeholder = LocalizationNotNeeded("Repeat passphrase")
                field.isSecureTextEntry = true
                field.autocorrectionType = .no
                field.autocapitalizationType = .none
            }
        }
        alert.addAction(UIAlertAction(title: CommonStrings.cancelButton, style: .cancel))
        alert.addAction(UIAlertAction(title: CommonStrings.okButton, style: .default) { [weak self, weak alert] _ in
            guard let self, let fields = alert?.textFields else { return }
            let passphrase = fields[0].text ?? ""
            guard passphrase.count >= 6 else {
                OWSActionSheets.showErrorAlert(
                    message: LocalizationNotNeeded("Use a passphrase of at least 6 characters."),
                    fromViewController: self,
                )
                return
            }
            if confirm, fields.count > 1, fields[1].text != passphrase {
                OWSActionSheets.showErrorAlert(
                    message: LocalizationNotNeeded("The passphrases do not match."),
                    fromViewController: self,
                )
                return
            }
            completion(passphrase)
        })
        present(alert, animated: true)
    }

    private func exportBackup() {
        guard MeshNodeService.shared.isRunning else {
            OWSActionSheets.showErrorAlert(
                message: LocalizationNotNeeded("Turn on the mesh transport first."),
                fromViewController: self,
            )
            return
        }
        promptPassphrase(
            title: LocalizationNotNeeded("Encrypt mesh backup"),
            message: LocalizationNotNeeded("Anyone with this passphrase and the file can restore your mesh identity and contacts. There is no way to recover it."),
            confirm: true,
        ) { [weak self] passphrase in
            guard let self else { return }
            ModalActivityIndicatorViewController.present(
                fromViewController: self,
                canCancel: false,
                backgroundBlock: { modal in
                    let result: Result<URL, Error> = Result {
                        let blob = try MeshNodeService.shared.exportBackup(passphrase: passphrase)
                        let formatter = DateFormatter()
                        formatter.dateFormat = "yyyy-MM-dd-HHmm"
                        let url = OWSFileSystem.temporaryFileUrl(
                            fileName: "Asher-mesh-\(formatter.string(from: Date())).asherbackup",
                            isAvailableWhileDeviceLocked: false,
                        )
                        try blob.write(to: url, options: [.atomic, .completeFileProtection])
                        return url
                    }
                    DispatchQueue.main.async {
                        modal.dismiss { [weak self] in
                            guard let self else { return }
                            switch result {
                            case .success(let url):
                                AttachmentSharing.showShareUI(for: url, sender: nil, from: self)
                            case .failure(let error):
                                OWSActionSheets.showErrorAlert(
                                    message: LocalizationNotNeeded("Backup failed: \(error.localizedDescription)"),
                                    fromViewController: self,
                                )
                            }
                        }
                    }
                },
            )
        }
    }

    private func pickBackupToRestore() {
        var contentTypes: [UTType] = [.data]
        if let asherBackup = UTType(filenameExtension: "asherbackup") {
            contentTypes.insert(asherBackup, at: 0)
        }
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: contentTypes, asCopy: true)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        present(picker, animated: true)
    }

    private func restore(blob: Data) {
        promptPassphrase(
            title: LocalizationNotNeeded("Restore mesh backup"),
            message: LocalizationNotNeeded("Enter the passphrase this backup was encrypted with."),
            confirm: false,
        ) { [weak self] passphrase in
            guard let self else { return }
            ModalActivityIndicatorViewController.present(
                fromViewController: self,
                canCancel: false,
                backgroundBlock: { modal in
                    let result: Result<String, Error> = Result {
                        try MeshNodeService.shared.restoreBackup(passphrase: passphrase, blob: blob)
                    }
                    DispatchQueue.main.async {
                        modal.dismiss { [weak self] in
                            guard let self else { return }
                            switch result {
                            case .success(let summary):
                                self.updateTableContents()
                                OWSActionSheets.showActionSheet(
                                    title: LocalizationNotNeeded("Mesh backup restored"),
                                    message: LocalizationNotNeeded(summary),
                                    fromViewController: self,
                                )
                            case .failure(let error):
                                OWSActionSheets.showErrorAlert(
                                    message: LocalizationNotNeeded("Restore failed: \(error.localizedDescription)"),
                                    fromViewController: self,
                                )
                            }
                        }
                    }
                },
            )
        }
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

// MARK: - UIDocumentPickerDelegate

extension MeshSettingsViewController: UIDocumentPickerDelegate {
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { return }
        let blob: Data
        do {
            let accessing = url.startAccessingSecurityScopedResource()
            defer {
                if accessing {
                    url.stopAccessingSecurityScopedResource()
                }
            }
            blob = try Data(contentsOf: url)
        } catch {
            OWSActionSheets.showErrorAlert(
                message: LocalizationNotNeeded("Could not read that file: \(error.localizedDescription)"),
                fromViewController: self,
            )
            return
        }
        // "ASHB" magic, version 1 (v3 contract). Cheap sanity check before asking for a passphrase.
        guard blob.count > 5, blob.prefix(4) == Data("ASHB".utf8) else {
            OWSActionSheets.showErrorAlert(
                message: LocalizationNotNeeded("That file is not an Asher mesh backup."),
                fromViewController: self,
            )
            return
        }
        restore(blob: blob)
    }
}

// MARK: - Report

/// A plain scrolling text view for the self-test report (monospaced, selectable).
class MeshReportViewController: OWSViewController {
    private let reportTitle: String
    private let text: String

    init(reportTitle: String, text: String) {
        self.reportTitle = reportTitle
        self.text = text
        super.init()
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = LocalizationNotNeeded(reportTitle)
        view.backgroundColor = .Signal.background

        let textView = UITextView()
        textView.isEditable = false
        textView.isSelectable = true
        textView.alwaysBounceVertical = true
        textView.backgroundColor = .clear
        textView.textColor = .Signal.label
        textView.font = UIFont.monospacedSystemFont(ofSize: 13, weight: .regular)
        textView.textContainerInset = UIEdgeInsets(top: 16, left: 12, bottom: 16, right: 12)
        textView.text = text
        view.addSubview(textView)
        textView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            textView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            textView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            textView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            textView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])

        navigationItem.rightBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .action,
            target: self,
            action: #selector(share),
        )
    }

    @objc
    private func share() {
        AttachmentSharing.showShareUI(for: text, sender: navigationItem.rightBarButtonItem, from: self)
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
