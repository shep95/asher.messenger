/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sender.noop;

import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.Collections;
import java.util.Map;
import org.signal.registration.Environments;
import org.signal.registration.sender.prescribed.PrescribedVerificationCodeRepository;

@Requires(env = Environments.DEVELOPMENT)
@Requires(missingBeans = PrescribedVerificationCodeRepository.class)
@Singleton
public class NoopPrescribedVerificationCodeRepository implements PrescribedVerificationCodeRepository {

  @Override
  public Map<Phonenumber.PhoneNumber, String> getVerificationCodes() {
    return Collections.emptyMap();
  }
}
