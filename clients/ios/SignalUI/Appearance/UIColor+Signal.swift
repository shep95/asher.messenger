//
// Copyright 2024 Signal Messenger, LLC
// SPDX-License-Identifier: AGPL-3.0-only
//

import SignalServiceKit
public import SwiftUI
import UIKit

// MARK: - Custom Colors -

extension UIColor {
    fileprivate static func byUserInterfaceLevel(
        base: UIColor,
        elevated: UIColor,
    ) -> UIColor {
        UIColor { traitCollection in
            if traitCollection.userInterfaceLevel == .elevated {
                elevated
            } else {
                base
            }
        }
    }

    public static func byRGBHex(
        light: UInt32,
        lightHighContrast: UInt32? = nil,
        dark: UInt32,
        darkHighContrast: UInt32? = nil,
    ) -> UIColor {
        UIColor(
            light: UIColor(rgbHex: light),
            lightHighContrast: lightHighContrast != nil ? UIColor(rgbHex: lightHighContrast!) : nil,
            dark: UIColor(rgbHex: dark),
            darkHighContrast: darkHighContrast != nil ? UIColor(rgbHex: darkHighContrast!) : nil,
        )
    }

    public convenience init(
        light: UIColor,
        lightHighContrast: UIColor? = nil,
        dark: UIColor,
        darkHighContrast: UIColor? = nil,
    ) {
        self.init { traitCollection in
            switch (traitCollection.userInterfaceStyle, traitCollection.accessibilityContrast) {
            case (.dark, .high) where darkHighContrast != nil:
                darkHighContrast!
            case (.dark, _):
                dark
            case (_, .high) where lightHighContrast != nil:
                lightHighContrast!
            case (_, _):
                light
            }
        }
    }
}

// MARK: - UIKit

extension UIColor {
    public enum Signal {}
}

extension UIColor.Signal {

    // MARK: Accent

    // Asher: the single accent is "atmosphere" blue (design tokens `color.atmosphere`).
    // The `ultramarine` name is kept so call sites stay untouched.
    public static var ultramarine: UIColor {
        UIColor.byRGBHex(
            light: 0x3D8FCF,
            lightHighContrast: 0x2E74AB,
            dark: 0x3D8FCF,
            darkHighContrast: 0x5AA7E0,
        )
    }

    public static var red: UIColor {
        UIColor.byRGBHex(
            light: 0xE06B6B,
            lightHighContrast: 0xC94F4F,
            dark: 0xE06B6B,
            darkHighContrast: 0xEB8A8A,
        )
    }

    public static var orange: UIColor {
        UIColor.byRGBHex(
            light: 0xE0B45A,
            lightHighContrast: 0xB8893A,
            dark: 0xE0B45A,
            darkHighContrast: 0xEBC77E,
        )
    }

    public static var yellow: UIColor {
        UIColor.byRGBHex(
            light: 0xFFCC00,
            lightHighContrast: 0xB25000,
            dark: 0xFFD60A,
            darkHighContrast: 0xFFD426,
        )
    }

    public static var green: UIColor {
        UIColor.byRGBHex(
            light: 0x4FBF9F,
            lightHighContrast: 0x2F9678,
            dark: 0x4FBF9F,
            darkHighContrast: 0x72D2B7,
        )
    }

    public static var indigo: UIColor {
        UIColor.byRGBHex(
            light: 0x5856D6,
            lightHighContrast: 0x3634A3,
            dark: 0x5E5CE6,
            darkHighContrast: 0x7D7AFF,
        )
    }

    public static var accent: UIColor { ultramarine }

    public static var link: UIColor { ultramarine }

    // MARK: Asher tokens

    // Values from brands/asher/design/tokens.json. Dark is the primary look;
    // light uses the tokens' `light` block on a cloud-white base.

    /// `color.atmosphere-hover`
    public static var asherAtmosphereHighlight: UIColor { UIColor(rgbHex: 0x5AA7E0) }
    /// `color.atmosphere-pressed`
    public static var asherAtmospherePressed: UIColor { UIColor(rgbHex: 0x2E74AB) }
    /// `color.atmosphere-soft`
    public static var asherAtmosphereSoft: UIColor {
        UIColor(light: UIColor(rgbHex: 0xCFE6F7), dark: UIColor(rgbHex: 0x1E466B))
    }
    /// `color.rim`
    public static var asherRim: UIColor { UIColor(rgbHex: 0xCFE6F7) }
    /// `color.surface-raised`
    public static var asherSurfaceRaised: UIColor {
        UIColor(light: UIColor(rgbHex: 0xEEF3F8), dark: UIColor(rgbHex: 0x111823))
    }
    /// `color.surface-overlay`
    public static var asherSurfaceOverlay: UIColor {
        UIColor(light: UIColor(rgbHex: 0xE4EBF2), dark: UIColor(rgbHex: 0x16202D))
    }
    /// `color.border`
    public static var asherBorder: UIColor {
        UIColor(light: UIColor(rgbHex: 0xD6DFE8), dark: UIColor(rgbHex: 0x1B2533))
    }
    /// `color.border-strong`
    public static var asherBorderStrong: UIColor {
        UIColor(light: UIColor(rgbHex: 0xB9C6D4), dark: UIColor(rgbHex: 0x27344A))
    }
    /// `color.text-muted`
    public static var asherTextMuted: UIColor {
        UIColor(light: UIColor(rgbHex: 0x8194A6), dark: UIColor(rgbHex: 0x5F7387))
    }
    /// `color.text-on-atmosphere`
    public static var asherTextOnAtmosphere: UIColor { UIColor(rgbHex: 0xF4F9FD) }
    /// `color.success`
    public static var asherSuccess: UIColor { UIColor(rgbHex: 0x4FBF9F) }
    /// `color.warning`
    public static var asherWarning: UIColor { UIColor(rgbHex: 0xE0B45A) }
    /// `color.danger`
    public static var asherDanger: UIColor { UIColor(rgbHex: 0xE06B6B) }
    /// `color.bubble-in`
    public static var asherBubbleIn: UIColor {
        UIColor(light: UIColor(rgbHex: 0xEEF3F8), dark: UIColor(rgbHex: 0x131C28))
    }
    /// `color.bubble-in-border`
    public static var asherBubbleInBorder: UIColor {
        UIColor(light: UIColor(rgbHex: 0xD6DFE8), dark: UIColor(rgbHex: 0x1E2A3A))
    }
    /// `composer.fill`
    public static var asherComposerFill: UIColor {
        UIColor(light: UIColor(rgbHex: 0xFFFFFF), dark: UIColor(rgbHex: 0x111823))
    }
    /// `color.presence-online`
    public static var asherPresenceOnline: UIColor { UIColor(rgbHex: 0x5AA7E0) }
    /// `color.presence-mesh`
    public static var asherPresenceMesh: UIColor { UIColor(rgbHex: 0x4FBF9F) }
    /// `color.presence-carrying`
    public static var asherPresenceCarrying: UIColor { UIColor(rgbHex: 0xE0B45A) }
    /// `color.presence-offline`
    public static var asherPresenceOffline: UIColor { UIColor(rgbHex: 0x5F7387) }

    // MARK: Label

    public static var label: UIColor {
        UIColor(
            light: UIColor(rgbHex: 0x0B1017),
            dark: UIColor(rgbHex: 0xE8EEF4),
        )
    }

    public static var secondaryLabel: UIColor {
        UIColor(
            light: UIColor(rgbHex: 0x4A5D70),
            lightHighContrast: UIColor(rgbHex: 0x2F4052),
            dark: UIColor(rgbHex: 0x9BB0C3),
            darkHighContrast: UIColor(rgbHex: 0xB9CBDB),
        )
    }

    public static var tertiaryLabel: UIColor {
        UIColor(
            light: UIColor(rgbHex: 0x8194A6),
            lightHighContrast: UIColor(rgbHex: 0x5F7387),
            dark: UIColor(rgbHex: 0x5F7387),
            darkHighContrast: UIColor(rgbHex: 0x8194A6),
        )
    }

    public static var quaternaryLabel: UIColor {
        UIColor(
            light: UIColor(rgbHex: 0x8194A6, alpha: 0.5),
            lightHighContrast: UIColor(rgbHex: 0x5F7387, alpha: 0.7),
            dark: UIColor(rgbHex: 0x5F7387, alpha: 0.5),
            darkHighContrast: UIColor(rgbHex: 0x8194A6, alpha: 0.7),
        )
    }

    public static var emphasisLabel: UIColor {
        UIColor(
            light: UIColor(rgbHex: 0xE06B6B),
            lightHighContrast: UIColor(rgbHex: 0xC94F4F),
            dark: UIColor(rgbHex: 0xE06B6B),
            darkHighContrast: UIColor(rgbHex: 0xEB8A8A),
        )
    }

    public static var warningLabel: UIColor {
        UIColor(
            light: UIColor(rgbHex: 0xB44828),
            dark: UIColor(rgbHex: 0xEB977D),
        )
    }

    public static var officialLabel: UIColor {
        UIColor(
            light: UIColor(rgbHex: 0x2934FD),
            dark: UIColor(rgbHex: 0xC5C7F5),
        )
    }

    public static var officialLabelBackground: UIColor {
        UIColor(
            light: UIColor(rgbHex: 0x2934FD).withAlphaComponent(0.12),
            dark: UIColor(rgbHex: 0x424585),
        )
    }

    // MARK: Background

    // Asher surfaces: void #05070B -> surface #0B1017 -> raised #111823 -> overlay #16202D.
    public static var background: UIColor {
        UIColor.byUserInterfaceLevel(
            base: UIColor.byRGBHex(
                light: 0xFFFFFF,
                dark: 0x05070B,
            ),
            elevated: UIColor.byRGBHex(
                light: 0xFFFFFF,
                dark: 0x0B1017,
                darkHighContrast: 0x111823,
            ),
        )
    }

    /// Background for all media content. Void in dark mode and white in light mode.
    /// Unlike `background` this color does not have "elevated" colors.
    public static var mediaBackground: UIColor {
        return UIColor(light: .white, dark: UIColor(rgbHex: 0x05070B))
    }

    public static var secondaryBackground: UIColor {
        guard #available(iOS 16.0, *) else {
            return .secondarySystemBackground
        }
        return UIColor.byUserInterfaceLevel(
            base: UIColor.byRGBHex(
                light: 0xEEF3F8,
                lightHighContrast: 0xD6DFE8,
                dark: 0x0B1017,
                darkHighContrast: 0x111823,
            ),
            elevated: UIColor.byRGBHex(
                light: 0xEEF3F8,
                lightHighContrast: 0xD6DFE8,
                dark: 0x111823,
                darkHighContrast: 0x16202D,
            ),
        )
    }

    public static var tertiaryBackground: UIColor {
        UIColor.byUserInterfaceLevel(
            base: UIColor.byRGBHex(
                light: 0xFFFFFF,
                dark: 0x111823,
                darkHighContrast: 0x16202D,
            ),
            elevated: UIColor.byRGBHex(
                light: 0xFFFFFF,
                dark: 0x16202D,
                darkHighContrast: 0x1B2533,
            ),
        )
    }

    public static var secondaryUltramarineBackground: UIColor {
        UIColor(light: UIColor(rgbHex: 0xCFE6F7), dark: UIColor(rgbHex: 0x1E466B))
    }

    public static var backdrop: UIColor {
        UIColor(
            light: UIColor(rgbHex: 0x05070B, alpha: 0.2),
            dark: UIColor(rgbHex: 0x05070B, alpha: 0.6),
        )
    }

    // MARK: Grouped Background

    public static var groupedBackground: UIColor {
        guard #available(iOS 16.0, *) else {
            return .systemGroupedBackground
        }
        return UIColor.byUserInterfaceLevel(
            base: UIColor.byRGBHex(
                light: 0xF4F7FA,
                lightHighContrast: 0xEEF3F8,
                dark: 0x05070B,
            ),
            elevated: UIColor.byRGBHex(
                light: 0xF4F7FA,
                lightHighContrast: 0xEEF3F8,
                dark: 0x0B1017,
                darkHighContrast: 0x111823,
            ),
        )
    }

    public static var secondaryGroupedBackground: UIColor {
        UIColor.byUserInterfaceLevel(
            base: UIColor.byRGBHex(
                light: 0xFFFFFF,
                dark: 0x0B1017,
                darkHighContrast: 0x111823,
            ),
            elevated: UIColor.byRGBHex(
                light: 0xFFFFFF,
                dark: 0x111823,
                darkHighContrast: 0x16202D,
            ),
        )
    }

    public static var tertiaryGroupedBackground: UIColor {
        guard #available(iOS 16.0, *) else {
            return .tertiarySystemGroupedBackground
        }
        return UIColor.byUserInterfaceLevel(
            base: UIColor.byRGBHex(
                light: 0xEEF3F8,
                lightHighContrast: 0xD6DFE8,
                dark: 0x111823,
                darkHighContrast: 0x16202D,
            ),
            elevated: UIColor.byRGBHex(
                light: 0xEEF3F8,
                lightHighContrast: 0xD6DFE8,
                dark: 0x16202D,
                darkHighContrast: 0x1B2533,
            ),
        )
    }

    // MARK: Fill

    public static var primaryFill: UIColor {
        UIColor(
            light: UIColor(rgbHex: 0x7F9AB5, alpha: 0.2),
            lightHighContrast: UIColor(rgbHex: 0x7F9AB5, alpha: 0.3),
            dark: UIColor(rgbHex: 0x7F9AB5, alpha: 0.36),
            darkHighContrast: UIColor(rgbHex: 0x7F9AB5, alpha: 0.46),
        )
    }

    public static var secondaryFill: UIColor {
        UIColor(
            light: UIColor(rgbHex: 0x7F9AB5, alpha: 0.16),
            lightHighContrast: UIColor(rgbHex: 0x7F9AB5, alpha: 0.26),
            dark: UIColor(rgbHex: 0x7F9AB5, alpha: 0.32),
            darkHighContrast: UIColor(rgbHex: 0x7F9AB5, alpha: 0.42),
        )
    }

    public static var tertiaryFill: UIColor {
        UIColor(
            light: UIColor(rgbHex: 0x7F9AB5, alpha: 0.12),
            lightHighContrast: UIColor(rgbHex: 0x7F9AB5, alpha: 0.22),
            dark: UIColor(rgbHex: 0x7F9AB5, alpha: 0.24),
            darkHighContrast: UIColor(rgbHex: 0x7F9AB5, alpha: 0.34),
        )
    }

    public static var quaternaryFill: UIColor {
        UIColor(
            light: UIColor(rgbHex: 0x7F9AB5, alpha: 0.08),
            lightHighContrast: UIColor(rgbHex: 0x7F9AB5, alpha: 0.18),
            dark: UIColor(rgbHex: 0x7F9AB5, alpha: 0.18),
            darkHighContrast: UIColor(rgbHex: 0x7F9AB5, alpha: 0.28),
        )
    }

    // MARK: Material

    /// Designed to be used on top of material (blur / glass) backgrounds.
    public enum MaterialBase {

        public static var fillPrimary: UIColor {
            UIColor(
                light: UIColor(white: 0, alpha: 0.24),
                dark: UIColor(white: 1, alpha: 0.48),
            )
        }

        public static var fillSecondary: UIColor {
            UIColor(
                light: UIColor(white: 0, alpha: 0.16),
                dark: UIColor(white: 1, alpha: 0.24),
            )
        }

        public static var fillTertiary: UIColor {
            UIColor(
                light: UIColor(white: 0, alpha: 0.1),
                dark: UIColor(white: 1, alpha: 0.16),
            )
        }

        public static var button: UIColor {
            UIColor(
                light: UIColor(white: 0, alpha: 0.12),
                dark: UIColor(white: 1, alpha: 0.2),
            )
        }
    }

    // MARK: Light

    /// To be used on top of neutral backgrounds
    /// (eg incoming message bubbles when no wallpaper).
    public enum LightBase {

        public static var fillPrimary: UIColor {
            UIColor(
                light: UIColor(white: 1, alpha: 1),
                dark: UIColor(white: 1, alpha: 0.48),
            )
        }

        public static var fillSecondary: UIColor {
            UIColor(
                light: UIColor(white: 1, alpha: 0.8),
                dark: UIColor(white: 1, alpha: 0.24),
            )
        }

        public static var fillTertiary: UIColor {
            UIColor(
                light: UIColor(white: 1, alpha: 0.6),
                dark: UIColor(white: 1, alpha: 0.16),
            )
        }

        public static var button: UIColor {
            UIColor(
                light: UIColor(white: 1, alpha: 0.8),
                dark: UIColor(white: 1, alpha: 0.2),
            )
        }
    }

    // MARK: Color

    /// To be used on top of any arbitrary color. Fixed across light/dark theme.
    public enum ColorBase {

        public static var labelPrimary: UIColor {
            UIColor(white: 1, alpha: 1)
        }

        public static var labelSecondary: UIColor {
            UIColor(white: 1, alpha: 0.8)
        }

        public static var labelTertiary: UIColor {
            UIColor(white: 1, alpha: 0.4)
        }

        public static var labelInverted: UIColor {
            UIColor(white: 0, alpha: 1)
        }

        public static var labelInvertedSecondary: UIColor {
            UIColor(white: 0, alpha: 0.7)
        }

        public static var fillPrimary: UIColor {
            UIColor(white: 1, alpha: 0.8)
        }

        public static var fillSecondary: UIColor {
            UIColor(white: 1, alpha: 0.7)
        }

        public static var fillTertiary: UIColor {
            UIColor(
                light: UIColor(white: 1, alpha: 0.6),
                dark: UIColor(white: 1, alpha: 0.48),
            )
        }

        public static var button: UIColor {
            UIColor(white: 1, alpha: 0.2)
        }
    }

    @available(iOS 26, *)
    public static var glassBackgroundTint: UIColor {
        UIColor(
            light: UIColor(white: 1, alpha: 0.12),
            dark: UIColor(white: 0, alpha: 0.2),
        )
    }

    // MARK: Separator

    public static var opaqueSeparator: UIColor {
        UIColor.byRGBHex(
            light: 0xD6DFE8,
            lightHighContrast: 0xB9C6D4,
            dark: 0x1B2533,
            darkHighContrast: 0x27344A,
        )
    }

    public static var transparentSeparator: UIColor {
        UIColor(
            light: UIColor(rgbHex: 0x4A5D70, alpha: 0.3),
            lightHighContrast: UIColor(rgbHex: 0x4A5D70, alpha: 0.45),
            dark: UIColor(rgbHex: 0x9BB0C3, alpha: 0.24),
            darkHighContrast: UIColor(rgbHex: 0x9BB0C3, alpha: 0.4),
        )
    }
}

// MARK: - SwiftUI

extension Color {
    public enum Signal {}
}

extension Color.Signal {

    // MARK: Accent

    public static var ultramarine: Color {
        Color(UIColor.Signal.ultramarine)
    }

    public static var red: Color {
        Color(UIColor.Signal.red)
    }

    public static var orange: Color {
        Color(UIColor.Signal.orange)
    }

    public static var yellow: Color {
        Color(UIColor.Signal.yellow)
    }

    public static var green: Color {
        Color(UIColor.Signal.green)
    }

    public static var indigo: Color {
        Color(UIColor.Signal.indigo)
    }

    public static var accent: Color { ultramarine }

    public static var link: Color { ultramarine }

    // MARK: Label

    public static var label: Color {
        Color(UIColor.Signal.label)
    }

    public static var secondaryLabel: Color {
        Color(UIColor.Signal.secondaryLabel)
    }

    public static var tertiaryLabel: Color {
        Color(UIColor.Signal.tertiaryLabel)
    }

    public static var quaternaryLabel: Color {
        Color(UIColor.Signal.quaternaryLabel)
    }

    public static var emphasisLabel: Color {
        Color(UIColor.Signal.emphasisLabel)
    }

    public static var warningLabel: Color {
        Color(UIColor.Signal.warningLabel)
    }

    // MARK: Background

    public static var background: Color {
        Color(UIColor.Signal.background)
    }

    public static var secondaryBackground: Color {
        Color(UIColor.Signal.secondaryBackground)
    }

    public static var tertiaryBackground: Color {
        Color(UIColor.Signal.tertiaryBackground)
    }

    // MARK: Grouped Background

    public static var groupedBackground: Color {
        Color(UIColor.Signal.groupedBackground)
    }

    public static var secondaryGroupedBackground: Color {
        Color(UIColor.Signal.secondaryGroupedBackground)
    }

    public static var tertiaryGroupedBackground: Color {
        Color(UIColor.Signal.tertiaryGroupedBackground)
    }

    // MARK: Fill

    public static var primaryFill: Color {
        Color(UIColor.Signal.primaryFill)
    }

    public static var secondaryFill: Color {
        Color(UIColor.Signal.secondaryFill)
    }

    public static var tertiaryFill: Color {
        Color(UIColor.Signal.tertiaryFill)
    }

    public static var quaternaryFill: Color {
        Color(UIColor.Signal.quaternaryFill)
    }

    // MARK: Separator

    public static var opaqueSeparator: Color {
        Color(UIColor.Signal.opaqueSeparator)
    }

    public static var transparentSeparator: Color {
        Color(UIColor.Signal.transparentSeparator)
    }

}
