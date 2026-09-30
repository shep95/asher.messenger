/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.lambda;

import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

class Account {

  @VisibleForTesting
  static final String KEY_ACCOUNT_UUID = "U";
  @VisibleForTesting
  static final String ATTR_ACCOUNT_E164 = "P";

  @VisibleForTesting
  static final String ATTR_PNI_UUID = "PNI";

  @VisibleForTesting
  static final String ATTR_CANONICALLY_DISCOVERABLE = "C";

  @VisibleForTesting
  static final String ATTR_UAK = "UAK";

  @JsonProperty
  @Nullable
  String e164;

  @JsonProperty
  byte[] uuid;

  @JsonProperty
  boolean canonicallyDiscoverable;

  @JsonProperty
  @Nullable
  byte[] pni;

  @JsonProperty
  @Nullable
  byte[] uak;

  Account() {
  }  // empty constructor for JSON

  Account(String e164, byte[] uuid, boolean canonicallyDiscoverable, byte[] pni, byte[] uak) {
    this.e164 = e164;
    this.uuid = uuid;
    this.canonicallyDiscoverable = canonicallyDiscoverable;
    this.pni = pni;
    this.uak = uak;
  }

  static Account fromItem(Map<String, AttributeValue> item) {
    Preconditions.checkNotNull(item.get(KEY_ACCOUNT_UUID));
    byte[] uuid = new byte[16];
    item.get(KEY_ACCOUNT_UUID).getB().get(uuid);

    final AttributeValue e164AttributeValue = item.get(ATTR_ACCOUNT_E164);
    final String e164 = e164AttributeValue != null ? e164AttributeValue.getS() : null;

    final byte[] pni = bytesFrom(item.get(ATTR_PNI_UUID));
    final byte[] uak = bytesFrom(item.get(ATTR_UAK));

    return new Account(
        e164,
        uuid,
        item.get(ATTR_CANONICALLY_DISCOVERABLE).getBOOL(),
        pni,
        uak);
  }

  private static byte[] bytesFrom(final AttributeValue attributeValue) {
    if (attributeValue == null || attributeValue.getB() == null) {
      return null;
    }
    final ByteBuffer bb = attributeValue.getB();
    final byte[] bytes = new byte[bb.remaining()];
    bb.get(bytes);
    return bytes;
  }

  Account forceNotInCds() {
    return new Account(e164, uuid, false, pni, uak);
  }

  // Partition such that the primary key (UUID) is maintained across streams.
  String partitionKey() {
    ByteBuffer s = ByteBuffer.wrap(uuid);
    return new UUID(s.getLong(), s.getLong()).toString();
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;
    Account account = (Account) o;
    return canonicallyDiscoverable == account.canonicallyDiscoverable &&
        Objects.equals(e164, account.e164) &&
        Arrays.equals(uuid, account.uuid) &&
        Arrays.equals(pni, account.pni) &&
        Arrays.equals(uak, account.uak);
  }

  @Override
  public String toString() {
    return "Account{" +
        "e164='" + e164 +
        "', uuid=" + Arrays.toString(uuid) +
        ", canonicallyDiscoverable=" + canonicallyDiscoverable +
        ", uak=" + Arrays.toString(uak) +
        ", pni=" + Arrays.toString(pni) +
        "}";
  }
}
