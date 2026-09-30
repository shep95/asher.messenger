package org.signal.registration.sinch;

import com.sinch.sdk.SinchClient;
import com.sinch.sdk.domains.sms.api.v1.BatchesService;
import com.sinch.sdk.models.Configuration;
import com.sinch.sdk.models.SMSRegion;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

@Factory
public class SinchClientFactory {

  @Singleton
  BatchesService smsClient(final SinchSmsClientConfiguration smsClientConfiguration) {
    return new SinchClient(Configuration.builder()
        .setSmsServicePlanId(smsClientConfiguration.servicePlanId())
        .setSmsApiToken(smsClientConfiguration.apiToken())
        .setSmsRegion(SMSRegion.from(smsClientConfiguration.smsRegion()))
        .build()
    ).sms().v1().batches();
  }

}
