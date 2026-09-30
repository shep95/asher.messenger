//
// Copyright 2024 Signal Messenger, LLC
// SPDX-License-Identifier: AGPL-3.0-only
//

import Foundation

extension FeatureBuild {
// Upstream rewrites this file in its release pipeline (Scripts/feature_flags_*.py); the checked-in
// value was `.internal`, which ships the Internal Settings screen (database + key export, plaintext
// proxy links, verbose logging) in any archive built from the tree as-is. Keep it at `.production`
// for release builds here and use the scripts to opt into other levels locally.
#if DEBUG
    static let current: FeatureBuild = .dev
#else
    static let current: FeatureBuild = .production
#endif
}
