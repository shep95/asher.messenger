// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

import { assert } from 'chai';

import {
  encodeKiss,
  KissCommand,
  KissDecoder,
  KISS_FEND,
  KISS_FESC,
  RADIO_CONFIG_EU_LONG_RANGE,
  radioConfigFrames,
  radioFrameMtu,
} from '../../mesh/kiss.std.ts';

describe('mesh/kiss', () => {
  it('escapes and decodes across arbitrary chunk boundaries', () => {
    const payload = Uint8Array.from([
      0x01,
      KISS_FEND,
      0x02,
      KISS_FESC,
      0x03,
      KISS_FEND,
      KISS_FEND,
    ]);
    const encoded = encodeKiss(KissCommand.Data, payload);
    assert.notInclude(
      Array.from(encoded.subarray(1, encoded.length - 1)),
      KISS_FEND
    );

    const decoder = new KissDecoder();
    const got = [];
    for (let i = 0; i < encoded.length; i += 3) {
      got.push(...decoder.feed(encoded.subarray(i, i + 3)));
    }
    assert.lengthOf(got, 1);
    assert.strictEqual(got[0]?.command, KissCommand.Data);
    assert.deepEqual(got[0]?.payload, payload);
  });

  it('handles back-to-back frames and line noise', () => {
    const a = encodeKiss(KissCommand.Data, new TextEncoder().encode('first'));
    const b = encodeKiss(KissCommand.Ready, new Uint8Array(0));
    const stream = Uint8Array.from([0x55, 0x66, ...a, ...b]);
    const got = new KissDecoder().feed(stream);
    assert.lengthOf(got, 2);
    assert.strictEqual(got[0]?.command, KissCommand.Data);
    assert.strictEqual(new TextDecoder().decode(got[0]?.payload), 'first');
    assert.strictEqual(got[1]?.command, KissCommand.Ready);
    assert.lengthOf(got[1]?.payload ?? [1], 0);
  });

  it('drops a frame with an invalid escape', () => {
    const bad = Uint8Array.from([
      KISS_FEND,
      KissCommand.Data,
      0x01,
      KISS_FESC,
      0x99,
      0x02,
      KISS_FEND,
    ]);
    assert.lengthOf(new KissDecoder().feed(bad), 0);
  });

  it('produces the EU long range radio config frames', () => {
    const frames = radioConfigFrames(RADIO_CONFIG_EU_LONG_RANGE);
    assert.lengthOf(frames, 6);
    assert.strictEqual(frames[0]?.[1], KissCommand.Frequency);
    // 868_000_000 Hz = 0x33BCA100, big-endian as `u32::to_be_bytes`.
    assert.deepEqual(
      Array.from(frames[0]?.subarray(2, 6) ?? []),
      [0x33, 0xbc, 0xa1, 0x00]
    );
    assert.deepEqual(
      Array.from(frames[5] ?? []),
      [KISS_FEND, KissCommand.RadioState, 0x01, KISS_FEND]
    );
    assert.strictEqual(radioFrameMtu(RADIO_CONFIG_EU_LONG_RANGE), 200);
    assert.strictEqual(
      radioFrameMtu({ ...RADIO_CONFIG_EU_LONG_RANGE, spreadingFactor: 12 }),
      120
    );
  });
});
