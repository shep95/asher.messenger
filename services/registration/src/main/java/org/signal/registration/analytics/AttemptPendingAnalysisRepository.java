/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics;

import org.signal.registration.sender.VerificationCodeSender;
import java.util.stream.Stream;

/**
 * An "attempt pending analysis" repository stores partial information about completed verification attempts with the
 * expectation that another component will provide additional information (price, for example) later. Implementations
 * should store attempts pending analysis on a best-effort basis and may discard them at any time for any reason.
 */
public interface AttemptPendingAnalysisRepository {

  /**
   * Stores an attempt pending analysis. If an attempt pending analysis already exists with the given sender name and
   * remote ID, it will be overwritten by the given event.
   *
   * @param attemptPendingAnalysis the attempt pending analysis to be stored
   */
  void store(AttemptPendingAnalysis attemptPendingAnalysis);

  /**
   * Returns a stream that yields all attempts pending analysis for the given sender.
   *
   * @param senderName the name of the sender for which to retrieve attempts pending analysis
   *
   * @return a stream that yields all attempts pending analysis for the given sender
   *
   * @see VerificationCodeSender#getName()
   */
  Stream<AttemptPendingAnalysis> getBySender(String senderName);

  /**
   * Removes an individual attempt pending analysis from this repository. Has no effect if the given attempt does not
   * exist within this repository.
   *
   * @param attemptPendingAnalysis the attempt to remove from this repository
   */
  void remove(AttemptPendingAnalysis attemptPendingAnalysis);
}
