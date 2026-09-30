//
// Copyright 2023 Signal Messenger, LLC
// SPDX-License-Identifier: AGPL-3.0-only
//

import SignalServiceKit
import UIKit

public extension UIFont {

    // MARK: - Icon Font

    class func awesomeFont(ofSize size: CGFloat) -> UIFont {
        return UIFont(name: "FontAwesome", size: size)!
    }

    // MARK: -

    class func regularFont(ofSize size: CGFloat) -> UIFont {
        return .systemFont(ofSize: size, weight: .regular)
    }

    /// Returns an instance of the system font with scaling for the user's
    /// selected content size category with the specified size being used for
    /// the standard Dynamic Type size.
    ///
    /// - Parameters:
    ///   - standardSize: The size the font should be for the standard Dynamic Type size.
    ///   - weight: The weight of the font.
    /// - Returns: The system font scaled to the current Dynamic Type setting.
    class func dynamicTypeFont(
        ofStandardSize standardSize: CGFloat,
        weight: UIFont.Weight = .regular,
    ) -> UIFont {
        return UIFontMetrics.default.scaledFont(for: .systemFont(ofSize: standardSize, weight: weight))
    }

    class func semiboldFont(ofSize size: CGFloat) -> UIFont {
        return .systemFont(ofSize: size, weight: .semibold)
    }

    class func monospacedDigitFont(ofSize size: CGFloat) -> UIFont {
        return .monospacedDigitSystemFont(ofSize: size, weight: .regular)
    }

    // MARK: - Asher type scale

    /// Asher `typography.scale` on the system font (tokens.json `mobile_note`):
    /// title 22/semibold, headline 17/semibold, body 16, message 16, caption 13,
    /// micro 11 (uppercase, 0.04em tracking). Text styles not listed here keep
    /// the system defaults.
    private static let asherScale: [UIFont.TextStyle: (size: CGFloat, weight: UIFont.Weight)] = [
        .title2: (22, .semibold),
        .headline: (17, .semibold),
        .body: (16, .regular),
        .callout: (16, .regular),
        .footnote: (13, .regular),
        .caption1: (13, .regular),
        .caption2: (11, .regular),
    ]

    /// The unscaled (Dynamic Type "Large") font for a text style under the Asher scale.
    private class func asherBaseFont(forTextStyle textStyle: UIFont.TextStyle) -> UIFont {
        if let entry = asherScale[textStyle] {
            return .systemFont(ofSize: entry.size, weight: entry.weight)
        }
        return UIFont.preferredFont(
            forTextStyle: textStyle,
            compatibleWith: UITraitCollection(preferredContentSizeCategory: .large),
        )
    }

    private class func asherPreferredFont(forTextStyle textStyle: UIFont.TextStyle) -> UIFont {
        UIFontMetrics(forTextStyle: textStyle).scaledFont(for: asherBaseFont(forTextStyle: textStyle), compatibleWith: .current)
    }

    /// Asher "micro" style: 11pt, uppercase, 0.04em tracking. Callers uppercase the text.
    class var asherMicro: UIFont { asherPreferredFont(forTextStyle: .caption2) }
    /// Tracking (kern) for `asherMicro`, in points (0.04em).
    class var asherMicroTracking: CGFloat { 11 * 0.04 }

    // MARK: - Dynamic Type

    class var dynamicTypeTitle1: UIFont { asherPreferredFont(forTextStyle: .title1) }

    class var dynamicTypeTitle2: UIFont { asherPreferredFont(forTextStyle: .title2) }

    class var dynamicTypeTitle3: UIFont { asherPreferredFont(forTextStyle: .title3) }

    class var dynamicTypeHeadline: UIFont { asherPreferredFont(forTextStyle: .headline) }

    class var dynamicTypeBody: UIFont { asherPreferredFont(forTextStyle: .body) }

    class var dynamicTypeCallout: UIFont { asherPreferredFont(forTextStyle: .callout) }

    class var dynamicTypeSubheadline: UIFont { asherPreferredFont(forTextStyle: .subheadline) }

    class var dynamicTypeFootnote: UIFont { asherPreferredFont(forTextStyle: .footnote) }

    class var dynamicTypeCaption1: UIFont { asherPreferredFont(forTextStyle: .caption1) }

    class var dynamicTypeCaption2: UIFont { asherPreferredFont(forTextStyle: .caption2) }

    // MARK: - Dynamic Type Clamped

    // We clamp the dynamic type sizes at the max size available
    // without "larger accessibility sizes" enabled.
    private static var maxPointSizeMap: [UIFont.TextStyle: CGFloat] = [
        .title1: 34,
        .title2: 28,
        .title3: 26,
        .headline: 23,
        .body: 23,
        .callout: 22,
        .subheadline: 21,
        .footnote: 19,
        .caption1: 18,
        .caption2: 17,
        .largeTitle: 40,
    ]

    private class func preferredFontClamped(forTextStyle textStyle: UIFont.TextStyle) -> UIFont {
        // From the documentation of -[id<UIContentSizeCategoryAdjusting> adjustsFontForContentSizeCategory:]
        // Dynamic sizing is only supported with fonts that are:
        // a. Vended using UIFont.preferredFont(forTextStyle:)
        // b. Vended from UIFontMetrics.scaledFont(for:) or one of its variants
        //
        // If we clamp fonts by checking the resulting point size and then creating a new, smaller UIFont with
        // a fallback max size, we'll lose dynamic sizing. Max sizes can be specified using UIFontMetrics though.
        //
        // UIFontMetrics will only operate on unscaled fonts. So we do this dance to cap the system default styles
        // 1. Grab the standard, unscaled font by using the default trait collection
        // 2. Use UIFontMetrics to scale it up, capped at the desired max size
        let unscaledFont = asherBaseFont(forTextStyle: textStyle)

        let desiredStyleMetrics = UIFontMetrics(forTextStyle: textStyle)
        guard let maxPointSize = maxPointSizeMap[textStyle] else {
            owsFailDebug("Missing max point size for style: \(textStyle)")
            return desiredStyleMetrics.scaledFont(for: unscaledFont)
        }
        return desiredStyleMetrics.scaledFont(
            for: unscaledFont,
            maximumPointSize: maxPointSize,
            compatibleWith: .current,
        )
    }

    class var dynamicTypeLargeTitle1Clamped: UIFont { preferredFontClamped(forTextStyle: .largeTitle) }
    class var dynamicTypeTitle1Clamped: UIFont { preferredFontClamped(forTextStyle: .title1) }
    class var dynamicTypeTitle2Clamped: UIFont { preferredFontClamped(forTextStyle: .title2) }
    class var dynamicTypeTitle3Clamped: UIFont { preferredFontClamped(forTextStyle: .title3) }
    class var dynamicTypeHeadlineClamped: UIFont { preferredFontClamped(forTextStyle: .headline) }
    class var dynamicTypeBodyClamped: UIFont { preferredFontClamped(forTextStyle: .body) }
    class var dynamicTypeCalloutClamped: UIFont { preferredFontClamped(forTextStyle: .callout) }
    class var dynamicTypeSubheadlineClamped: UIFont { preferredFontClamped(forTextStyle: .subheadline) }
    class var dynamicTypeFootnoteClamped: UIFont { preferredFontClamped(forTextStyle: .footnote) }
    class var dynamicTypeCaption1Clamped: UIFont { preferredFontClamped(forTextStyle: .caption1) }
    class var dynamicTypeCaption2Clamped: UIFont { preferredFontClamped(forTextStyle: .caption2) }

    // MARK: -

    func italic() -> UIFont {
        guard let fontDescriptor = fontDescriptor.withSymbolicTraits(.traitItalic) else { return self }
        return UIFont(descriptor: fontDescriptor, size: 0)
    }

    func medium() -> UIFont {
        let fontTraits = [UIFontDescriptor.TraitKey.weight: UIFont.Weight.medium]
        let fontDescriptor = fontDescriptor.addingAttributes([.traits: fontTraits])
        return UIFont(descriptor: fontDescriptor, size: 0)
    }

    func semibold() -> UIFont {
        let fontTraits = [UIFontDescriptor.TraitKey.weight: UIFont.Weight.semibold]
        let fontDescriptor = fontDescriptor.addingAttributes([.traits: fontTraits])
        return UIFont(descriptor: fontDescriptor, size: 0)
    }

    func bold() -> UIFont {
        let fontTraits = [UIFontDescriptor.TraitKey.weight: UIFont.Weight.bold]
        let fontDescriptor = fontDescriptor.addingAttributes([.traits: fontTraits])
        return UIFont(descriptor: fontDescriptor, size: 0)
    }

    func monospaced() -> UIFont {
        return .monospacedDigitFont(ofSize: pointSize)
    }

}
