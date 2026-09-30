/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
@Configuration
@Requires(env = Environments.ANALYTICS)
package org.signal.registration.analytics.infobip.bigtable;

import io.micronaut.context.annotation.Configuration;
import io.micronaut.context.annotation.Requires;
import org.signal.registration.Environments;
