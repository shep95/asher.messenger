/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.infobip;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.Money;
import org.signal.registration.analytics.PriceEstimator;
import org.signal.registration.analytics.infobip.bigtable.BigtableInfobipDefaultSmsPricesRepository;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;

@Singleton
public class InfobipSmsPriceEstimator implements PriceEstimator {

  private final BigtableInfobipDefaultSmsPricesRepository defaultSmsPricesRepository;
  private final Currency defaultPriceCurrency;

  public InfobipSmsPriceEstimator(final BigtableInfobipDefaultSmsPricesRepository defaultSmsPricesRepository,
                                  @Value("${analytics.infobip.sms.default-price-currency:USD}") final String defaultPriceCurrency) {

    this.defaultSmsPricesRepository = defaultSmsPricesRepository;
    this.defaultPriceCurrency = Currency.getInstance(defaultPriceCurrency);
  }

  @Override
  public Optional<Money> estimatePrice(final AttemptPendingAnalysis attemptPendingAnalysis,
      @Nullable final String mcc,
      @Nullable final String mnc) {

    final Optional<BigDecimal> maybePriceFromMccMnc = StringUtils.isNoneBlank(mcc, mnc)
        ? defaultSmsPricesRepository.get(mcc + mnc)
        : Optional.empty();

    return maybePriceFromMccMnc
        .or(() -> defaultSmsPricesRepository.get(attemptPendingAnalysis.getRegion()))
        .map(price -> new Money(price, defaultPriceCurrency));
  }
}
