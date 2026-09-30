/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.analytics.sinch;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.validation.constraints.NotBlank;

@Context
@ConfigurationProperties("sinch.sms.price-sheet.gcp")
@Requires(env = Environment.GOOGLE_COMPUTE)
@Requires(notEnv = Environment.TEST)
public record GcpSinchPriceSheetConfiguration(@NotBlank String bucketName, @NotBlank String objectName) {
}
