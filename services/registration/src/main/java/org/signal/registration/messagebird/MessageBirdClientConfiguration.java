/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.messagebird;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;

@Context
@ConfigurationProperties("messagebird")
public record MessageBirdClientConfiguration(@NotBlank String accessKey,
                                             @Bindable(defaultValue = "PT5S") Duration connectTimeout,
                                             @Bindable(defaultValue = "PT5S") Duration readTimeout) {
}
