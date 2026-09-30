// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

import { assert } from 'chai';

import {
  fingerprintFromHex,
  fingerprintToHex,
  fingerprintToMeshServiceId,
  fingerprintsEqual,
} from '../../mesh/address.std.ts';
import { isValidUuid } from '../../util/isValidUuid.std.ts';

describe('mesh/address', () => {
  const fingerprint = fingerprintFromHex('00112233445566778899aabbccddeeff');

  it('renders and parses the 32-hex fingerprint', () => {
    assert.strictEqual(
      fingerprintToHex(fingerprint),
      '00112233445566778899aabbccddeeff'
    );
    assert.isTrue(
      fingerprintsEqual(
        fingerprintFromHex(fingerprintToHex(fingerprint)),
        fingerprint
      )
    );
    assert.throws(() => fingerprintFromHex('abc'));
  });

  it('renders a serviceId Desktop accepts, keeping all but six bits', () => {
    const serviceId = fingerprintToMeshServiceId(fingerprint);
    assert.strictEqual(serviceId, '00112233-4455-4677-8899-aabbccddeeff');
    assert.isTrue(isValidUuid(serviceId));
    // Deterministic, and the input is not modified.
    assert.strictEqual(fingerprintToMeshServiceId(fingerprint), serviceId);
    assert.strictEqual(fingerprintToHex(fingerprint), '00112233445566778899aabbccddeeff');
  });

  it('forces the version and variant bits for any input', () => {
    const allOnes = new Uint8Array(16).fill(0xff);
    assert.strictEqual(
      fingerprintToMeshServiceId(allOnes),
      'ffffffff-ffff-4fff-bfff-ffffffffffff'
    );
    assert.isTrue(isValidUuid(fingerprintToMeshServiceId(new Uint8Array(16))));
  });
});
