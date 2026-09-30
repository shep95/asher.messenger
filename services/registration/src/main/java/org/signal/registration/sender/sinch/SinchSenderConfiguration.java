/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.sinch;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import jakarta.validation.constraints.NotBlank;
import org.signal.registration.sender.SenderIdSelectorConfiguration;
import java.util.Map;

@Context
@ConfigurationProperties("sinch")
public record SinchSenderConfiguration(
    @NotBlank String defaultSenderId,
    Map<@NotBlank String, @NotBlank String> senderIdsByRegion) implements SenderIdSelectorConfiguration
{}
