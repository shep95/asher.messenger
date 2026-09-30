package org.signal.registration.analytics.twilio;

import org.junit.jupiter.api.Test;
import org.signal.registration.analytics.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CarrierFeeAdjusterTest {
  private static final BigDecimal US_CARRIER_FEE = BigDecimal.valueOf(0.0025);
  private static final BigDecimal CA_CARRIER_FEE = BigDecimal.valueOf(0.003);

  @Test
  public void addCarrierFee() {
    final CarrierFeeAdjuster adjuster = new CarrierFeeAdjuster(Map.of(
        "US", US_CARRIER_FEE,
        "CA", CA_CARRIER_FEE
    ), "USD");

    final Money basePrice = new Money(BigDecimal.valueOf(0.001), Currency.getInstance("USD"));
    final Money usCarrierFee = new Money(US_CARRIER_FEE, Currency.getInstance("USD"));
    final Money caCarrierFee = new Money(CA_CARRIER_FEE, Currency.getInstance("USD"));

    assertEquals(basePrice, adjuster.addCarrierFeeIfApplicable(basePrice, "GB"));
    assertEquals(basePrice.add(usCarrierFee), adjuster.addCarrierFeeIfApplicable(basePrice, "US"));
    assertEquals(basePrice.add(caCarrierFee), adjuster.addCarrierFeeIfApplicable(basePrice, "CA"));
  }
}
