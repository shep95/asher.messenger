package org.signal.registration.analytics.twilio;

import io.micronaut.context.annotation.EachBean;
import io.micronaut.context.annotation.Factory;

@Factory
public class CarrierFeeAdjusterFactory {
  @EachBean(CarrierFeeConfiguration.class)
  CarrierFeeAdjuster carrierFeeAdjuster(CarrierFeeConfiguration config) {
    return new CarrierFeeAdjuster(config.carrierFeeByRegion(), config.carrierFeeCurrency());
  }
}
