//
// Copyright 2016 Signal Messenger, LLC
// SPDX-License-Identifier: AGPL-3.0-only
//

import SignalServiceKit
import UIKit

// MARK: - Asher rim light

/// Asher `avatar.ring`: a 1.5pt ring lit from the top-left (#7FB8E0) that fades
/// to nothing by 70% along the 135 degree diagonal, like the planet's limb.
public enum AsherRimLight {
    public static let width: CGFloat = 1.5

    public static func makeLayer() -> CAGradientLayer {
        let gradientLayer = CAGradientLayer()
        gradientLayer.type = .axial
        gradientLayer.colors = [
            UIColor(rgbHex: 0x7FB8E0).cgColor,
            UIColor(rgbHex: 0x7FB8E0, alpha: 0).cgColor,
        ]
        gradientLayer.locations = [0, 0.7]
        gradientLayer.startPoint = CGPoint(x: 0, y: 0)
        gradientLayer.endPoint = CGPoint(x: 1, y: 1)
        let ringMask = CAShapeLayer()
        ringMask.fillRule = .evenOdd
        gradientLayer.mask = ringMask
        return gradientLayer
    }

    /// Lays the ring over `frame` (in the superlayer's coordinate space).
    /// Pass `isVisible: false` for rectangular or empty avatars.
    public static func layout(_ gradientLayer: CAGradientLayer, in frame: CGRect, isVisible: Bool) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        gradientLayer.isHidden = !isVisible || frame.isEmpty
        gradientLayer.frame = frame
        if let ringMask = gradientLayer.mask as? CAShapeLayer {
            let bounds = CGRect(origin: .zero, size: frame.size)
            let ringPath = UIBezierPath(ovalIn: bounds)
            ringPath.append(UIBezierPath(ovalIn: bounds.insetBy(dx: width, dy: width)))
            ringMask.frame = bounds
            ringMask.path = ringPath.cgPath
        }
        CATransaction.commit()
    }
}

// MARK: -

open class AvatarImageView: UIImageView, CVView {

    public var shouldDeactivateConstraints = false

    private let rimLightLayer = AsherRimLight.makeLayer()

    public init() {
        super.init(frame: .zero)
        self.configureView()
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        self.configureView()
    }

    public required init?(coder aDecoder: NSCoder) {
        super.init(coder: aDecoder)
        self.configureView()
    }

    override public init(image: UIImage?) {
        super.init(image: image)
        self.configureView()
    }

    public init(shouldDeactivateConstraints: Bool) {
        self.shouldDeactivateConstraints = shouldDeactivateConstraints
        super.init(frame: .zero)
        self.configureView()
    }

    func configureView() {
        self.autoPinToSquareAspectRatio()

        self.layer.minificationFilter = .trilinear
        self.layer.magnificationFilter = .trilinear
        self.layer.masksToBounds = true

        self.contentMode = .scaleToFill
    }

    override public func layoutSubviews() {
        super.layoutSubviews()
        layer.cornerRadius = frame.size.width / 2

        if rimLightLayer.superlayer == nil {
            layer.addSublayer(rimLightLayer)
        }
        AsherRimLight.layout(rimLightLayer, in: bounds, isVisible: image != nil)
    }

    override public func updateConstraints() {
        super.updateConstraints()

        if shouldDeactivateConstraints {
            deactivateAllConstraints()
        }
    }

    public func reset() {
        self.image = nil
    }
}

// MARK: -

public class AvatarImageButton: UIButton {

    // MARK: - Button Overrides

    override public func layoutSubviews() {
        super.layoutSubviews()

        layer.cornerRadius = frame.size.width / 2
    }

    override public func setImage(_ image: UIImage?, for state: UIControl.State) {
        ensureViewConfigured()
        super.setImage(image, for: state)
    }

    // MARK: Private

    var hasBeenConfigured = false
    func ensureViewConfigured() {
        guard !hasBeenConfigured else {
            return
        }
        hasBeenConfigured = true

        autoPinToSquareAspectRatio()

        layer.minificationFilter = .trilinear
        layer.magnificationFilter = .trilinear
        layer.masksToBounds = true

        contentMode = .scaleToFill
    }
}
