/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

@Configuration
@Requires(property = "sinch.sms.client.service-plan-id")
@Requires(property = "sinch.sms.client.api-token")
package org.signal.registration.sender.sinch;

import io.micronaut.context.annotation.Configuration;
import io.micronaut.context.annotation.Requires;

