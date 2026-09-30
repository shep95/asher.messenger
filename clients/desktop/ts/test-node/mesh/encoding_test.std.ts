// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

import { assert } from 'chai';

import {
  decodeMeshEvent,
  decodeMeshGroupCreate,
  decodeMeshNearby,
  decodeMeshPrepared,
  decodeMeshStats,
  linkKey,
  MeshWireError,
} from '../../mesh/encoding.std.ts';
import { MeshAttachmentKind, MeshEventTag } from '../../mesh/constants.std.ts';

// A tiny mirror of meshlink's wire writer so the tests build fixtures the
// same way the Rust bridge does (big-endian, u16 length prefixes).
class Writer {
  bytes: Array<number> = [];

  u8(v: number): this {
    this.bytes.push(v & 0xff);
    return this;
  }

  u16(v: number): this {
    return this.u8(v >> 8).u8(v);
  }

  u32(v: number): this {
    return this.u16(v >>> 16).u16(v & 0xffff);
  }

  u64(v: bigint): this {
    for (let shift = 56n; shift >= 0n; shift -= 8n) {
      this.u8(Number((v >> shift) & 0xffn));
    }
    return this;
  }

  lenBytes32(v: Uint8Array): this {
    return this.u32(v.length).fixed(v);
  }

  string(v: string): this {
    return this.lenBytes(new TextEncoder().encode(v));
  }

  fixed(v: Uint8Array): this {
    this.bytes.push(...v);
    return this;
  }

  lenBytes(v: Uint8Array): this {
    return this.u16(v.length).fixed(v);
  }

  finish(): Uint8Array {
    return Uint8Array.from(this.bytes);
  }
}

const id = (fill: number): Uint8Array => new Uint8Array(16).fill(fill);

describe('mesh/encoding', () => {
  it('returns undefined for an empty event buffer (timeout)', () => {
    assert.isUndefined(decodeMeshEvent(new Uint8Array(0)));
  });

  it('decodes Ciphertext events', () => {
    const ciphertext = Uint8Array.from([9, 8, 7]);
    const data = new Writer()
      .u8(MeshEventTag.Ciphertext)
      .fixed(id(1))
      .fixed(id(2))
      .fixed(id(3))
      .u8(3)
      .lenBytes(ciphertext)
      .finish();
    const event = decodeMeshEvent(data);
    assert.isDefined(event);
    assert.strictEqual(event?.kind, 'ciphertext');
    if (event?.kind !== 'ciphertext') {
      return;
    }
    assert.deepEqual(event.from, id(1));
    assert.deepEqual(event.bundleId, id(2));
    assert.deepEqual(event.ackCommit, id(3));
    assert.strictEqual(event.messageType, 3);
    assert.deepEqual(event.ciphertext, ciphertext);
  });

  it('decodes Message events with the known_sender flag', () => {
    const plaintext = new TextEncoder().encode('hello');
    const data = new Writer()
      .u8(MeshEventTag.Message)
      .fixed(id(4))
      .fixed(id(5))
      .u8(1)
      .lenBytes(plaintext)
      .finish();
    const event = decodeMeshEvent(data);
    assert.strictEqual(event?.kind, 'message');
    if (event?.kind !== 'message') {
      return;
    }
    assert.isTrue(event.knownSender);
    assert.strictEqual(new TextDecoder().decode(event.plaintext), 'hello');
  });

  it('decodes group, contact, delivered, neighbour and link events', () => {
    const groupMessage = decodeMeshEvent(
      new Writer()
        .u8(MeshEventTag.GroupMessage)
        .fixed(id(1))
        .fixed(id(2))
        .fixed(id(3))
        .lenBytes(Uint8Array.from([1]))
        .finish()
    );
    assert.strictEqual(groupMessage?.kind, 'groupMessage');

    const invite = decodeMeshEvent(
      new Writer()
        .u8(MeshEventTag.GroupInvite)
        .fixed(id(1))
        .fixed(id(2))
        .finish()
    );
    assert.strictEqual(invite?.kind, 'groupInvite');

    const contact = decodeMeshEvent(
      new Writer().u8(MeshEventTag.Contact).fixed(id(7)).finish()
    );
    assert.strictEqual(contact?.kind, 'contact');

    const delivered = decodeMeshEvent(
      new Writer().u8(MeshEventTag.Delivered).fixed(id(8)).finish()
    );
    assert.strictEqual(delivered?.kind, 'delivered');

    const neighbour = decodeMeshEvent(
      new Writer()
        .u8(MeshEventTag.Neighbour)
        .u64(0x0102030405060708n)
        .fixed(id(9))
        .finish()
    );
    assert.strictEqual(neighbour?.kind, 'neighbour');
    if (neighbour?.kind === 'neighbour') {
      assert.strictEqual(neighbour.link, 0x0102030405060708n);
      assert.strictEqual(linkKey(neighbour.link), '72623859790382856');
    }

    const closed = decodeMeshEvent(
      new Writer().u8(MeshEventTag.LinkClosed).u64(42n).finish()
    );
    assert.strictEqual(closed?.kind, 'linkClosed');
    if (closed?.kind === 'linkClosed') {
      assert.strictEqual(closed.link, 42n);
      assert.strictEqual(linkKey(closed.link), linkKey(42));
    }
  });

  it('decodes AttachmentProgress (tag 9)', () => {
    const event = decodeMeshEvent(
      new Writer()
        .u8(MeshEventTag.AttachmentProgress)
        .fixed(id(1))
        .fixed(id(2))
        .u32(3)
        .u32(1400)
        .finish()
    );
    assert.strictEqual(event?.kind, 'attachmentProgress');
    if (event?.kind !== 'attachmentProgress') {
      return;
    }
    assert.deepEqual(event.from, id(1));
    assert.deepEqual(event.transfer, id(2));
    assert.strictEqual(event.received, 3);
    assert.strictEqual(event.total, 1400);
  });

  it('decodes Attachment (tag 10) with a u32 data length', () => {
    const data = new Uint8Array(70_000).fill(0xab);
    const event = decodeMeshEvent(
      new Writer()
        .u8(MeshEventTag.Attachment)
        .fixed(id(3))
        .fixed(id(4))
        .u8(MeshAttachmentKind.VoiceNote)
        .string('note.aac')
        .string('audio/aac')
        .lenBytes32(data)
        .finish()
    );
    assert.strictEqual(event?.kind, 'attachment');
    if (event?.kind !== 'attachment') {
      return;
    }
    assert.deepEqual(event.from, id(3));
    assert.deepEqual(event.transfer, id(4));
    assert.strictEqual(event.attachmentKind, 3);
    assert.strictEqual(event.name, 'note.aac');
    assert.strictEqual(event.mime, 'audio/aac');
    assert.strictEqual(event.data.length, 70_000);
    assert.strictEqual(event.data[69_999], 0xab);

    // Empty name and mime are allowed (the receiver picks a default).
    const bare = decodeMeshEvent(
      new Writer()
        .u8(MeshEventTag.Attachment)
        .fixed(id(3))
        .fixed(id(4))
        .u8(MeshAttachmentKind.File)
        .string('')
        .string('')
        .lenBytes32(Uint8Array.of(1))
        .finish()
    );
    assert.strictEqual(bare?.kind, 'attachment');
    if (bare?.kind === 'attachment') {
      assert.strictEqual(bare.name, '');
      assert.strictEqual(bare.mime, '');
    }
  });

  it('decodes CallSignal (tag 11)', () => {
    const payload = Uint8Array.from([0x0a, 0x02, 0x08, 0x01]);
    const event = decodeMeshEvent(
      new Writer()
        .u8(MeshEventTag.CallSignal)
        .fixed(id(5))
        .fixed(id(6))
        .lenBytes(payload)
        .finish()
    );
    assert.strictEqual(event?.kind, 'callSignal');
    if (event?.kind !== 'callSignal') {
      return;
    }
    assert.deepEqual(event.from, id(5));
    assert.deepEqual(event.bundleId, id(6));
    assert.deepEqual(event.data, payload);
  });

  it('decodes the nearby list in order', () => {
    const list = decodeMeshNearby(
      new Writer()
        .u16(2)
        .fixed(id(7))
        .string('Ada')
        .u64(1_700_000_000n)
        .u8(1)
        .fixed(id(8))
        .string('')
        .u64(1_699_999_000n)
        .u8(0)
        .finish()
    );
    assert.lengthOf(list, 2);
    assert.deepEqual(list[0]?.fingerprint, id(7));
    assert.strictEqual(list[0]?.name, 'Ada');
    assert.strictEqual(list[0]?.lastSeenSecs, 1_700_000_000);
    assert.isTrue(list[0]?.direct);
    assert.strictEqual(list[1]?.name, '');
    assert.isFalse(list[1]?.direct);

    assert.lengthOf(decodeMeshNearby(new Writer().u16(0).finish()), 0);
    assert.throws(
      () => decodeMeshNearby(new Writer().u16(1).fixed(id(1)).finish()),
      MeshWireError
    );
  });

  it('rejects unknown tags, truncation and trailing bytes', () => {
    assert.throws(() => decodeMeshEvent(Uint8Array.of(99)), MeshWireError);
    assert.throws(
      () =>
        decodeMeshEvent(new Writer().u8(MeshEventTag.Contact).u8(1).finish()),
      MeshWireError
    );
    assert.throws(
      () =>
        decodeMeshEvent(
          new Writer().u8(MeshEventTag.Delivered).fixed(id(1)).u8(0).finish()
        ),
      MeshWireError
    );
  });

  it('decodes prepared lists and group-create results', () => {
    const one = new Writer()
      .u16(2)
      .fixed(id(1))
      .fixed(id(2))
      .lenBytes(Uint8Array.from([1, 2]))
      .fixed(id(3))
      .fixed(id(4))
      .lenBytes(new Uint8Array(0))
      .finish();
    const items = decodeMeshPrepared(one);
    assert.lengthOf(items, 2);
    assert.deepEqual(items[0]?.to, id(1));
    assert.deepEqual(items[0]?.commit, id(2));
    assert.deepEqual(items[0]?.plaintext, Uint8Array.from([1, 2]));
    assert.lengthOf(items[1]?.plaintext ?? [1], 0);

    const created = decodeMeshGroupCreate(
      new Writer().fixed(id(6)).fixed(one).finish()
    );
    assert.deepEqual(created.groupId, id(6));
    assert.lengthOf(created.prepared, 2);
  });

  it('decodes stats in field order and tolerates version skew', () => {
    const w = new Writer();
    for (let i = 1; i <= 18; i += 1) {
      w.u64(BigInt(i));
    }
    const stats = decodeMeshStats(w.finish());
    assert.strictEqual(stats.framesIn, 1n);
    assert.strictEqual(stats.messagesDelivered, 9n);
    assert.strictEqual(stats.links, 15n);
    assert.strictEqual(stats.outstanding, 18n);

    // Shorter (older crate): missing fields read as zero.
    const short = decodeMeshStats(new Writer().u64(5n).finish());
    assert.strictEqual(short.framesIn, 5n);
    assert.strictEqual(short.outstanding, 0n);

    // Longer (newer crate): extra counters are ignored.
    w.u64(99n);
    assert.strictEqual(decodeMeshStats(w.finish()).outstanding, 18n);
  });
});
