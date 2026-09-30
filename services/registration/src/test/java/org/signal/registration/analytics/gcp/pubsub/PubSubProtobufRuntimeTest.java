/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.analytics.gcp.pubsub;


import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.google.api.gax.core.CredentialsProvider;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.gcp.pubsub.annotation.PubSubClient;
import io.micronaut.gcp.pubsub.annotation.Topic;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.signal.registration.Environments;
import org.testcontainers.containers.PubSubEmulatorContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@MicronautTest(environments = {Environments.ANALYTICS, Environment.GOOGLE_COMPUTE})
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PubSubProtobufRuntimeTest implements TestPropertyProvider {

  private static final String GCLOUD_EMULATOR_IMAGE_NAME =
      "gcr.io/google.com/cloudsdktool/cloud-sdk:" + System.getProperty("gcloud.emulator.version", "emulators");

  private static final String PROJECT = "test";
  private static final String TOPIC = "testTopic";
  private static final String EXECUTOR_NAME = "test";

  @Container
  private static final PubSubEmulatorContainer CONTAINER =
      new PubSubEmulatorContainer(DockerImageName.parse(GCLOUD_EMULATOR_IMAGE_NAME));

  @Bean
  @Named(EXECUTOR_NAME)
  ScheduledExecutorService executorService() {
    return Executors.newSingleThreadScheduledExecutor();
  }

  @Inject
  TransportChannelProvider transportChannelProvider;

  @Inject
  CredentialsProvider credentialsProvider;

  @Inject
  private TestClient testClient;

  @BeforeEach
  void test() throws Exception {
    try (final TopicAdminClient topicAdminClient =
        TopicAdminClient.create(TopicAdminSettings.newBuilder()
            .setTransportChannelProvider(transportChannelProvider)
            .setCredentialsProvider(credentialsProvider)
            .build())) {

      topicAdminClient.createTopic(String.format("projects/%s/topics/%s", PROJECT, TOPIC));
    }
  }

  @Test
  void testSendEvent() {
    assertDoesNotThrow(() -> testClient.send(new byte[]{'t', 'e', 's', 't'}), "Should not throw ExceptionInInitializerError caused by RuntimeVersion$ProtobufRuntimeVersionException");
  }

  @Override
  public @NonNull Map<String, String> getProperties() {
    // The container must be started to get the emulator endpoint
    CONTAINER.start();

    return Map.of(
        // see io.micronaut.gcp.pubsub.support.PubSubConfigurationFactory#localChannelProvider
        "pubsub.emulator.host", CONTAINER.getEmulatorEndpoint(),
        "gcp.project-id", PROJECT,
        "gcp.pubsub.publishing-executor", EXECUTOR_NAME
    );
  }

  @PubSubClient
  public interface TestClient {

    @Topic(value = TOPIC, contentType = "application/protobuf")
    void send(byte[] data);
  }

}
