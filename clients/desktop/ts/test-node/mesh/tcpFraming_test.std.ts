// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

import { assert } from 'chai';

import {
  encodeTcpFrame,
  TcpFrameDecoder,
  TcpFramingError,
} from '../../mesh/tcpFraming.std.ts';

describe('mesh/tcpFraming', () => {
  it('round trips frames split across reads', () => {
    const a = Uint8Array.from([1, 2, 3]);
    const b = new Uint8Array(300).fill(7);
    const stream = Uint8Array.from([...encodeTcpFrame(a), ...encodeTcpFrame(b)]);
    assert.deepEqual(Array.from(stream.subarray(0, 2)), [0, 3]);

    const decoder = new TcpFrameDecoder();
    const frames = [];
    for (let i = 0; i < stream.length; i += 7) {
      frames.push(...decoder.feed(stream.subarray(i, i + 7)));
    }
    assert.lengthOf(frames, 2);
    assert.deepEqual(frames[0], a);
    assert.deepEqual(frames[1], b);
  });

  it('rejects zero and oversized lengths', () => {
    assert.throws(
      () => new TcpFrameDecoder().feed(Uint8Array.from([0, 0])),
      TcpFramingError
    );
    assert.throws(
      () => new TcpFrameDecoder().feed(Uint8Array.from([0xff, 0xff])),
      TcpFramingError
    );
    assert.throws(() => encodeTcpFrame(new Uint8Array(0)), TcpFramingError);
  });
});
