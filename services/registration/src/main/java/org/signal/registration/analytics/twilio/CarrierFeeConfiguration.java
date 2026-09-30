package org.signal.registration.analytics.twilio;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.core.bind.annotation.Bindable;
import java.math.BigDecimal;
import java.util.Map;

@EachProperty(value = "analytics.twilio", includes={"sms", "verify"})
record CarrierFeeConfiguration(Map<String, BigDecimal> carrierFeeByRegion, @Bindable(defaultValue = "USD") String carrierFeeCurrency) {}
