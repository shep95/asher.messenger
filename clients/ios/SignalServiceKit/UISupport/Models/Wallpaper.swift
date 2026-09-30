//
// Copyright 2024 Signal Messenger, LLC
// SPDX-License-Identifier: AGPL-3.0-only
//

import Foundation

public enum Wallpaper: String, CaseIterable {
    // Built-in photo (Asher): Earth from low orbit. Listed first so it leads the picker.
    case asherEarth

    // Solid
    case blush
    case copper
    case zorba
    case envy
    case sky
    case wildBlueYonder
    case lavender
    case shocking
    case gray
    case eden
    case violet
    case eggplant

    // Gradient
    case starshipGradient
    case woodsmokeGradient
    case coralGradient
    case ceruleanGradient
    case roseGradient
    case aquamarineGradient
    case tropicalGradient
    case blueGradient
    case bisqueGradient

    // Custom
    case photo

    // Release Notes
    case releaseNotes

    public static var defaultWallpapers: [Wallpaper] { allCases.filter { $0 != .photo && $0 != .releaseNotes } }

    /// The wallpaper rendered when nothing has been chosen (Asher default for new installs).
    public static let defaultForNewInstalls: Wallpaper = .asherEarth

    /// Image asset name (`Images.xcassets`) backing a built-in photo wallpaper, if any.
    public var builtInImageName: String? {
        switch self {
        case .asherEarth: return "asher_earth"
        default: return nil
        }
    }
}
