package org.signal.storageservice.configuration;

import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretVersionName;
import io.dropwizard.configuration.ConfigurationSourceProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import org.apache.commons.lang3.StringUtils;

public class SecretManagerConfigurationSourceProvider implements ConfigurationSourceProvider {

  private final URI secretUri;

  private static final String SCHEME = "secretmanager";

  public SecretManagerConfigurationSourceProvider(final String secretUri) {
    this(URI.create(secretUri));
  }

  public SecretManagerConfigurationSourceProvider(final URI secretUri) {
    if (!SCHEME.equalsIgnoreCase(secretUri.getScheme())) {
      throw new IllegalArgumentException("Unexpected URI scheme: " + secretUri.getScheme());
    }

    this.secretUri = secretUri;
  }

  @Override
  public InputStream open(final String ignored) throws IOException {
    final String projectId = secretUri.getHost();
    final String secretId = StringUtils.stripStart(secretUri.getPath(), "/");
    final String secretVersion = StringUtils.defaultIfBlank(secretUri.getFragment(), "latest");

    return new ByteArrayInputStream(fetchSecret(projectId, secretId, secretVersion));
  }

  private static byte[] fetchSecret(final String projectId, final String secretId, final String secretVersion)
      throws IOException {

    try (final SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
      return client.accessSecretVersion(SecretVersionName.of(projectId, secretId, secretVersion)).getPayload().getData().toByteArray();
    }
  }
}
