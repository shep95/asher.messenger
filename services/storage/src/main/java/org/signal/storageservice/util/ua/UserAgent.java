/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.storageservice.util.ua;

import com.vdurmont.semver4j.Semver;

import javax.annotation.Nullable;

public record UserAgent(ClientPlatform platform, Semver version, @Nullable String additionalSpecifiers) {
}
