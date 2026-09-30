package org.signal.registration.analytics.twilio;

import org.signal.registration.analytics.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/// A carrier fee is an additional fee charged per message by carriers in the US and Canada. It varies based on carrier.
/// Unfortunately, Twilio's pricing APIs do not reflect this fee, and we do not have carrier-level data available
/// when analyzing attempts, so we adjust by adding a market-share weighted average if the message was sent
/// to an applicable region.
public class CarrierFeeAdjuster {
  private final Map<String, Money> carrierFeesByRegion;

  public CarrierFeeAdjuster(
      final Map<String, BigDecimal> carrierFeeByRegion,
      final String carrierFeeCurrency) {
    this.carrierFeesByRegion = carrierFeeByRegion.entrySet().stream().collect(
        Collectors.toMap(
            entry -> entry.getKey().toUpperCase(Locale.ROOT),
            entry -> new Money(entry.getValue(), Currency.getInstance(carrierFeeCurrency))
        )
    );
  }

  Money addCarrierFeeIfApplicable(final Money price, final String regionCode) {
    return Optional.ofNullable(carrierFeesByRegion.get(regionCode))
        .map(fee -> new Money(price.amount().add(fee.amount()), price.currency()))
        .orElse(price);
  }
}
