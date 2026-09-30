//
// Copyright 2018 Signal Messenger, LLC
// SPDX-License-Identifier: AGPL-3.0-only
//

import SignalServiceKit
import SignalUI

protocol ConversationHeaderViewDelegate: AnyObject {
    func didTapConversationHeaderView(_ conversationHeaderView: ConversationHeaderView)
    func didTapConversationHeaderViewAvatar(_ conversationHeaderView: ConversationHeaderView)
}

class ConversationHeaderView: UIView {

    weak var delegate: ConversationHeaderViewDelegate?

    var titleIcon: UIImage? {
        get {
            return titleIconView.image
        }
        set {
            titleIconView.image = newValue
            titleIconView.isHidden = newValue == nil
        }
    }

    let titleLabel: UILabel = {
        let label = UILabel()
        label.textColor = UIColor.Signal.label
        label.lineBreakMode = .byTruncatingTail
        label.font = .systemFont(ofSize: 17, weight: .semibold)
        label.setContentHuggingHigh()
        return label
    }()

    let subtitleLabel: UILabel = {
        let label = UILabel()
        label.textColor = .Signal.label
        label.lineBreakMode = .byTruncatingTail
        label.font = .systemFont(ofSize: 13, weight: .medium)
        label.setContentHuggingHigh()
        return label
    }()

    private let titleIconView: UIImageView = {
        let titleIconView = UIImageView()
        titleIconView.isHidden = true
        titleIconView.contentMode = .scaleAspectFit
        titleIconView.setCompressionResistanceHigh()
        return titleIconView
    }()

    @available(iOS, deprecated: 26)
    private var useCompactVerticalLayout: Bool {
        // iPhones in portrait, iPads etc.
        if traitCollection.verticalSizeClass == .regular { return false }

        // Most recent iOS versions (starting with 17) seem to always have 44 dp navigation bar,
        // even on smaller devices in landscape.
        if bounds.size.height >= 44 { return false }

        return true
    }

    private var avatarSizeClass: ConversationAvatarView.Configuration.SizeClass {
        // Asher `avatar.sizes.header` (32pt); one size for the navigation bar on iOS 26.
        if #available(iOS 26, *) { return .thirtyTwo }

        return useCompactVerticalLayout ? .twentyFour : .thirtyTwo
    }

    /// Asher scene indicator: how this conversation is connected right now.
    let sceneIndicatorView = SceneIndicatorView()

    private(set) lazy var avatarView = ConversationAvatarView(
        sizeClass: avatarSizeClass,
        localUserDisplayMode: .noteToSelf,
    )

    override init(frame: CGRect) {
        super.init(frame: frame)

        translatesAutoresizingMaskIntoConstraints = false

        let titleColumns = UIStackView(arrangedSubviews: [titleLabel, titleIconView])
        titleColumns.spacing = 5
        titleColumns.translatesAutoresizingMaskIntoConstraints = false
        // There is a strange bug where an initial height of 0
        // breaks the layout, so set an initial height.
        titleColumns.heightAnchor.constraint(greaterThanOrEqualToConstant: titleLabel.font.lineHeight.rounded(.up)).isActive = true

        // Subtitle (mute / timer / verified) and the scene indicator share the row under the title.
        let subtitleRow = UIStackView(arrangedSubviews: [subtitleLabel, sceneIndicatorView])
        subtitleRow.axis = .horizontal
        subtitleRow.alignment = .center
        subtitleRow.spacing = 6

        let textRows = UIStackView(arrangedSubviews: [titleColumns, subtitleRow])
        textRows.axis = .vertical
        textRows.alignment = .leading
        textRows.distribution = .fillProportionally

        let rootStack = UIStackView(arrangedSubviews: [avatarView, textRows])
        rootStack.directionalLayoutMargins = .init(hMargin: 0, vMargin: 4)
        if #available(iOS 26, *) {
            // Default iOS 26 spacing between round back button and this view's leading edge is 12 pts.
            // We want 16 pts between back button and profile picture.
            rootStack.directionalLayoutMargins.leading = 4
        }
        rootStack.isLayoutMarginsRelativeArrangement = true
        rootStack.axis = .horizontal
        rootStack.alignment = .center
        // Larger profile picture on iOS 26 requires larger padding on both sides.
        rootStack.spacing = if #available(iOS 26, *) { 12 } else { 8 }

        addSubview(rootStack)
        rootStack.translatesAutoresizingMaskIntoConstraints = false
        titleIconView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            titleIconView.heightAnchor.constraint(equalToConstant: 16),
            titleIconView.widthAnchor.constraint(equalTo: titleIconView.heightAnchor),

            rootStack.topAnchor.constraint(equalTo: topAnchor),
            rootStack.leadingAnchor.constraint(equalTo: leadingAnchor),
            rootStack.trailingAnchor.constraint(equalTo: trailingAnchor),
            rootStack.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])

        // Embed a small glass view behind the avatar so that it's never visible to the user.
        // Glass views react to content underneath and update appearance (light / dark)
        // automatically. Using newer API for detecting trait collection changes it's now
        // possible to attach a small handler that will force UILabels to have
        // the same light or dark style as the glass view.
        if
            #available(iOS 26, *),
            CurrentAppContext().appUserDefaults().bool(forKey: "DisableChatHeaderContentTracking") == false
        {
            let glassTrackingView = UIVisualEffectView(effect: UIGlassEffect(style: .regular))
            rootStack.insertSubview(glassTrackingView, at: 0)
            glassTrackingView.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                glassTrackingView.widthAnchor.constraint(equalToConstant: 10),
                glassTrackingView.heightAnchor.constraint(equalToConstant: 10),
                glassTrackingView.centerXAnchor.constraint(equalTo: avatarView.centerXAnchor),
                glassTrackingView.centerYAnchor.constraint(equalTo: avatarView.centerYAnchor),
            ])

            glassTrackingView.contentView.registerForTraitChanges(
                [UITraitUserInterfaceStyle.self],
                handler: { [weak textRows] (view: UIView, _) in
                    textRows?.overrideUserInterfaceStyle = view.traitCollection.userInterfaceStyle
                },
            )
        }

        if #available(iOS 26, *) {
            heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        }

        let tapGesture = UITapGestureRecognizer(target: self, action: #selector(didTapView))
        rootStack.addGestureRecognizer(tapGesture)

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(connectivityDidChange),
            name: SSKReachability.owsReachabilityDidChange,
            object: nil,
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(connectivityDidChange),
            name: OWSChatConnection.chatConnectionStateDidChange,
            object: nil,
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(connectivityDidChange),
            name: .meshStatusDidChange,
            object: nil,
        )
        updateSceneIndicator()
    }

    required init(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    // MARK: Scene indicator

    /// A state set by the mesh transport (`.mesh(hops:)`, `.carrying`) wins over
    /// the connectivity-derived state until cleared with `nil`.
    private var transportStateOverride: SceneIndicatorView.State?

    /// The contact thread's address, so the mesh transport can report
    /// `.mesh(hops:)` / `.carrying` for it (FeatureFlags.meshTransport).
    private var meshThreadAddress: SignalServiceAddress?

    /// For the mesh transport: report `.mesh(hops:)` / `.carrying`, or `nil` to
    /// fall back to reachability (Orbit / Out of range).
    func setTransportState(_ state: SceneIndicatorView.State?) {
        AssertIsOnMainThread()
        transportStateOverride = state
        updateSceneIndicator()
    }

    @objc
    private func connectivityDidChange() {
        DispatchQueue.main.async { [weak self] in
            self?.updateSceneIndicator()
        }
    }

    private func updateSceneIndicator() {
        if let transportStateOverride {
            sceneIndicatorView.state = transportStateOverride
            return
        }
        if let meshState = meshTransportState() {
            sceneIndicatorView.state = meshState
            return
        }
        let isReachable = SSKEnvironment.shared.reachabilityManagerRef.isReachable
        sceneIndicatorView.state = isReachable ? .orbit : .offline
    }

    /// Mesh transport state for this thread's contact, or nil when the
    /// recipient is not a mesh contact / nothing mesh-related applies.
    private func meshTransportState() -> SceneIndicatorView.State? {
        guard FeatureFlags.meshTransport, let aci = meshThreadAddress?.aci else {
            return nil
        }
        switch MeshNodeService.shared.transportState(forContactAci: aci) {
        case .mesh(let hops)?:
            return .mesh(hops: hops)
        case .carrying?:
            return .carrying
        case nil:
            return nil
        }
    }

    func configure(threadViewModel: ThreadViewModel) {
        meshThreadAddress = (threadViewModel.threadRecord as? TSContactThread)?.contactAddress
        updateSceneIndicator()
        avatarView.updateWithSneakyTransactionIfNecessary { config in
            if threadViewModel.threadRecord.isReleaseNotesThread {
                config.dataSource = .asset(avatar: AvatarBuilder.releaseNotesIcon(), badge: nil)
            } else {
                config.dataSource = .thread(threadViewModel.threadRecord)
            }

            config.storyConfiguration = .autoUpdate()
            config.applyConfigurationSynchronously()
        }
    }

    override var bounds: CGRect {
        didSet {
            if #unavailable(iOS 26), oldValue.height != bounds.height {
                updateVerticalLayoutIfNecessary()
            }
        }
    }

    override var intrinsicContentSize: CGSize {
        // Grow to fill as much of the navbar as possible.
        return .init(width: .greatestFiniteMagnitude, height: UIView.noIntrinsicMetric)
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)

        if #unavailable(iOS 26), traitCollection.verticalSizeClass != previousTraitCollection?.verticalSizeClass {
            updateVerticalLayoutIfNecessary()
        }
    }

    @available(iOS, deprecated: 26)
    private func updateVerticalLayoutIfNecessary() {
        avatarView.updateWithSneakyTransactionIfNecessary { config in
            config.sizeClass = avatarSizeClass
        }
        // Single line of text when vertically compact layout.
        subtitleLabel.isHidden = useCompactVerticalLayout
        sceneIndicatorView.isHidden = useCompactVerticalLayout
    }

    // MARK: Spinning Title

    func updateTitleSpinning() {
        let key = "spin"
        if InMemorySettings.spinningConversationTitle {
            guard layer.animation(forKey: key) == nil else { return }
            let animation = CABasicAnimation(keyPath: "transform.rotation.z")
            animation.toValue = NSNumber(value: Double.pi * 2)
            animation.duration = 1
            animation.isCumulative = true
            animation.repeatCount = .greatestFiniteMagnitude
            layer.add(animation, forKey: key)
        } else {
            layer.removeAnimation(forKey: key)
        }
    }

    // MARK: Delegate Methods

    @objc
    private func didTapView(tapGesture: UITapGestureRecognizer) {
        guard tapGesture.state == .recognized else {
            return
        }

        if avatarView.bounds.contains(tapGesture.location(in: avatarView)) {
            self.delegate?.didTapConversationHeaderViewAvatar(self)
        } else {
            self.delegate?.didTapConversationHeaderView(self)
        }
    }
}

// MARK: - Scene indicator

/// Asher `scene_indicator`: a pill under the conversation title that says how
/// the two of you are connected right now. It is the one place the transport
/// shows itself.
final class SceneIndicatorView: UIView {

    enum State: Equatable {
        /// Internet reachable.
        case orbit
        /// Peer reachable over radio, N hops away.
        case mesh(hops: Int)
        /// Queued, waiting for a relay.
        case carrying
        /// Nothing.
        case offline

        // Transport names are brand terms (tokens.json `naming.transport_names`).
        var label: String {
            switch self {
            case .orbit: return "Orbit"
            case .mesh(let hops): return "Mesh \u{00B7} \(hops) \(hops == 1 ? "hop" : "hops")"
            case .carrying: return "Carrying"
            case .offline: return "Out of range"
            }
        }

        var color: UIColor {
            switch self {
            case .orbit: return .Signal.asherPresenceOnline
            case .mesh: return .Signal.asherPresenceMesh
            case .carrying: return .Signal.asherPresenceCarrying
            case .offline: return .Signal.asherPresenceOffline
            }
        }

        var isActive: Bool {
            if case .offline = self { return false }
            return true
        }
    }

    private static let dotSize: CGFloat = 6
    private static let pillHeight: CGFloat = 18
    private static let pulseKey = "asherBreathe"

    private let dotView = UIView()
    private let label = UILabel()

    var state: State = .offline {
        didSet {
            guard state != oldValue else { return }
            applyState()
        }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)

        backgroundColor = .Signal.asherSurfaceRaised
        layer.cornerRadius = Self.pillHeight / 2
        layer.borderWidth = 1
        layer.borderColor = UIColor.Signal.asherBorder.cgColor
        directionalLayoutMargins = .init(top: 0, leading: 8, bottom: 0, trailing: 8)
        isAccessibilityElement = true

        dotView.layer.cornerRadius = Self.dotSize / 2
        // Soft 8pt glow of the same colour.
        dotView.layer.shadowOffset = .zero
        dotView.layer.shadowRadius = 4
        dotView.layer.shadowOpacity = 0.9

        label.font = .asherMicro
        label.adjustsFontForContentSizeCategory = true
        label.textColor = .Signal.secondaryLabel
        label.setContentHuggingHigh()
        label.setCompressionResistanceHigh()

        let stack = UIStackView(arrangedSubviews: [dotView, label])
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = 6
        addSubview(stack)

        stack.translatesAutoresizingMaskIntoConstraints = false
        dotView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            dotView.widthAnchor.constraint(equalToConstant: Self.dotSize),
            dotView.heightAnchor.constraint(equalToConstant: Self.dotSize),
            stack.leadingAnchor.constraint(equalTo: layoutMarginsGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: layoutMarginsGuide.trailingAnchor),
            stack.topAnchor.constraint(equalTo: topAnchor),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor),
            heightAnchor.constraint(equalToConstant: Self.pillHeight),
        ])
        setContentHuggingHigh()
        setCompressionResistanceHigh()

        applyState()

        // CoreAnimation animations are dropped in the background; restore the pulse.
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(didBecomeActive),
            name: .OWSApplicationDidBecomeActive,
            object: nil,
        )
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func applyState() {
        let color = state.color
        dotView.backgroundColor = color
        dotView.layer.shadowColor = color.cgColor
        label.attributedText = NSAttributedString(
            string: state.label.uppercased(),
            attributes: [
                .font: UIFont.asherMicro,
                .kern: UIFont.asherMicroTracking,
            ],
        )
        accessibilityLabel = state.label
        updatePulse()
    }

    /// 2s breathing pulse while active; still while out of range or under Reduce Motion.
    private func updatePulse() {
        dotView.layer.removeAnimation(forKey: Self.pulseKey)
        guard state.isActive, !UIAccessibility.isReduceMotionEnabled else { return }

        let pulse = CABasicAnimation(keyPath: "opacity")
        pulse.fromValue = 1
        pulse.toValue = 0.6
        pulse.duration = AsherMotion.breath / 2
        pulse.autoreverses = true
        pulse.repeatCount = .greatestFiniteMagnitude
        pulse.timingFunction = AsherMotion.easeInOut
        dotView.layer.add(pulse, forKey: Self.pulseKey)
    }

    @objc
    private func didBecomeActive() {
        updatePulse()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        layer.borderColor = UIColor.Signal.asherBorder.cgColor
    }
}
