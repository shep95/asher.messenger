/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sinch;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import jakarta.validation.constraints.NotBlank;

@Context
@ConfigurationProperties("sinch.sms.client")
public record SinchSmsClientConfiguration(@NotBlank String servicePlanId, @NotBlank String apiToken, @NotBlank String smsRegion) {
}
