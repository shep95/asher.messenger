//
// Copyright 2018 Signal Messenger, LLC
// SPDX-License-Identifier: AGPL-3.0-only
//

import SignalServiceKit
import SignalUI

class TypingIndicatorView: ManualStackView {
    // Asher `typing_indicator`: three 6pt dots with a 4pt gap. Each dot rises
    // 3pt and brightens from text-muted to rim over 900ms, staggered 150ms.
    private static let kDotMaxHSpacing: CGFloat = 4

    static let kMinRadiusPt: CGFloat = 6
    static let kMaxRadiusPt: CGFloat = 6

    private static let kDotRise: CGFloat = 3
    private static let kDotRiseDuration: CFTimeInterval = 0.9
    private static let kDotStagger: CFTimeInterval = 0.15

    private let dot1 = DotView(dotType: .dotType1)
    private let dot2 = DotView(dotType: .dotType2)
    private let dot3 = DotView(dotType: .dotType3)

    private var cachedMeasurement: ManualStackView.Measurement?

    init() {
        super.init(name: "TypingIndicatorView")
    }

    @available(*, unavailable, message: "use other constructor instead.")
    required init(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    // MARK: - Notifications

    @objc
    private func didBecomeActive() {
        AssertIsOnMainThread()

        // CoreAnimation animations are stopped in the background, so ensure
        // animations are restored if necessary.
        if isAnimating {
            startAnimation()
        }
    }

    // MARK: -

    func configureForChatList() {
        if let measurement = self.cachedMeasurement {
            self.configureForReuse(
                config: Self.stackConfig,
                measurement: measurement,
            )
        } else {
            let measurement = Self.measurement()
            self.cachedMeasurement = measurement
            self.configure(
                config: Self.stackConfig,
                measurement: measurement,
                subviews: [dot1, dot2, dot3],
            )
        }

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(didBecomeActive),
            name: .OWSApplicationDidBecomeActive,
            object: nil,
        )
    }

    func configureForConversationView(cellMeasurement: CVCellMeasurement) {
        self.configure(
            config: Self.stackConfig,
            cellMeasurement: cellMeasurement,
            measurementKey: Self.measurementKey_stack,
            subviews: [dot1, dot2, dot3],
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(didBecomeActive),
            name: .OWSApplicationDidBecomeActive,
            object: nil,
        )
    }

    private static var stackConfig: CVStackViewConfig {
        CVStackViewConfig(
            axis: .horizontal,
            alignment: .center,
            spacing: kDotMaxHSpacing,
            layoutMargins: .zero,
        )
    }

    private static let measurementKey_stack = "TypingIndicatorView.measurementKey_stack"

    static func measurement() -> ManualStackView.Measurement {
        let dotSize = CGSize.square(kMaxRadiusPt)
        let subviewInfos = [
            dotSize.asManualSubviewInfo(hasFixedSize: true),
            dotSize.asManualSubviewInfo(hasFixedSize: true),
            dotSize.asManualSubviewInfo(hasFixedSize: true),
        ]
        return ManualStackView.measure(config: stackConfig, subviewInfos: subviewInfos)
    }

    static func measure(measurementBuilder: CVCellMeasurement.Builder) -> CGSize {
        let measurement = Self.measurement()
        measurementBuilder.setMeasurement(key: Self.measurementKey_stack, value: measurement)
        return measurement.measuredSize
    }

    override func reset() {
        super.reset()

        self.cachedMeasurement = nil

        stopAnimation()

        NotificationCenter.default.removeObserver(self)
    }

    func resetForReuse() {
        stopAnimation()

        NotificationCenter.default.removeObserver(self)
    }

    private func dots() -> [DotView] {
        return [dot1, dot2, dot3]
    }

    private var isAnimating = false

    func startAnimation() {
        isAnimating = true

        for dot in dots() {
            dot.startAnimation()
        }
    }

    func stopAnimation() {
        isAnimating = false

        for dot in dots() {
            dot.stopAnimation()
        }
    }

    private enum DotType {
        case dotType1
        case dotType2
        case dotType3

        var index: Int {
            switch self {
            case .dotType1: return 0
            case .dotType2: return 1
            case .dotType3: return 2
            }
        }
    }

    private class DotView: UIView {
        private let dotType: DotType

        private let shapeLayer = CAShapeLayer()

        @available(*, unavailable, message: "use other constructor instead.")
        required init?(coder aDecoder: NSCoder) {
            fatalError("init(coder:) has not been implemented")
        }

        @available(*, unavailable, message: "use other constructor instead.")
        override init(frame: CGRect) {
            fatalError("init(frame:) has not been implemented")
        }

        init(dotType: DotType) {
            self.dotType = dotType

            super.init(frame: .zero)

            layer.addSublayer(shapeLayer)
        }

        fileprivate func startAnimation() {
            stopAnimation()

            let dotSize = TypingIndicatorView.kMaxRadiusPt
            let dotRect = CGRect(x: 0, y: 0, width: dotSize, height: dotSize)
            shapeLayer.frame = dotRect
            shapeLayer.path = UIBezierPath(ovalIn: dotRect).cgPath

            let mutedColor = UIColor.Signal.asherTextMuted.resolvedColor(with: traitCollection).cgColor
            let rimColor = UIColor.Signal.asherRim.cgColor
            shapeLayer.fillColor = mutedColor

            // One cycle: rise and settle over 900ms, then rest while the other
            // two dots take their turn (2 x 150ms stagger).
            let riseDuration = TypingIndicatorView.kDotRiseDuration
            let stagger = TypingIndicatorView.kDotStagger
            let cycleDuration = riseDuration + 2 * stagger
            let keyTimes: [NSNumber] = [
                0,
                NSNumber(value: (riseDuration / 2) / cycleDuration),
                NSNumber(value: riseDuration / cycleDuration),
                1,
            ]
            let timingFunctions = [AsherMotion.easeInOut, AsherMotion.easeInOut, AsherMotion.easeInOut]

            let brighten = CAKeyframeAnimation(keyPath: "fillColor")
            brighten.values = [mutedColor, rimColor, mutedColor, mutedColor]
            brighten.keyTimes = keyTimes
            brighten.timingFunctions = timingFunctions

            var animations: [CAAnimation] = [brighten]

            // Reduce Motion: brighten only, no rise.
            if !UIAccessibility.isReduceMotionEnabled {
                let rise = CAKeyframeAnimation(keyPath: "transform.translation.y")
                rise.values = [0, -TypingIndicatorView.kDotRise, 0, 0]
                rise.keyTimes = keyTimes
                rise.timingFunctions = timingFunctions
                animations.append(rise)
            }

            let groupAnimation = CAAnimationGroup()
            groupAnimation.animations = animations
            groupAnimation.duration = cycleDuration
            groupAnimation.repeatCount = .greatestFiniteMagnitude
            groupAnimation.beginTime = CACurrentMediaTime() + stagger * CFTimeInterval(dotType.index)

            shapeLayer.add(groupAnimation, forKey: "asherTyping")
        }

        fileprivate func stopAnimation() {
            shapeLayer.removeAllAnimations()
        }
    }
}
