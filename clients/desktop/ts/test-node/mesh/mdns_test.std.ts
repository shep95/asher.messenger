// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// oxlint-disable no-bitwise -- hand-built DNS packets with compression pointers.

import { assert } from 'chai';

import {
  DnsType,
  MESH_SERVICE_NAME,
  MdnsError,
  decodeDnsMessage,
  encodeDnsMessage,
  encodeMeshAnnouncement,
  encodeMeshQuery,
  ipv4ToBytes,
  ipv6ToBytes,
  isMeshQuery,
  meshHostName,
  meshInstanceName,
  parseMeshPeers,
} from '../../mesh/mdns.std.ts';

const FP = '0123456789abcdef0123456789abcdef';
const OTHER_FP = 'fedcba9876543210fedcba9876543210';

describe('mesh/mdns', () => {
  it('names the service, instance and host from the fingerprint', () => {
    assert.strictEqual(MESH_SERVICE_NAME, '_asher-mesh._tcp.local');
    assert.strictEqual(
      meshInstanceName(FP.toUpperCase()),
      `${FP}._asher-mesh._tcp.local`
    );
    assert.strictEqual(meshHostName(FP), 'asher-0123456789ab.local');
  });

  it('encodes a query that is recognised as one for our service', () => {
    const bytes = encodeMeshQuery();
    // Header: id 0, flags 0, qd 1, an 0, ns 0, ar 0.
    assert.deepEqual(
      Array.from(bytes.subarray(0, 12)),
      [0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0]
    );
    const message = decodeDnsMessage(bytes);
    assert.isFalse(message.isResponse);
    assert.lengthOf(message.questions, 1);
    assert.strictEqual(message.questions[0]?.type, DnsType.PTR);
    assert.strictEqual(message.questions[0]?.name, MESH_SERVICE_NAME);
    assert.isTrue(isMeshQuery(message));

    const any = decodeDnsMessage(
      encodeDnsMessage({
        isResponse: false,
        questions: [
          {
            name: '_ASHER-MESH._tcp.local',
            type: DnsType.ANY,
            unicastResponse: true,
          },
        ],
      })
    );
    assert.isTrue(isMeshQuery(any));
    assert.isTrue(any.questions[0]?.unicastResponse);

    const unrelated = decodeDnsMessage(
      encodeDnsMessage({
        isResponse: false,
        questions: [
          {
            name: '_http._tcp.local',
            type: DnsType.PTR,
            unicastResponse: false,
          },
        ],
      })
    );
    assert.isFalse(isMeshQuery(unrelated));
    assert.isFalse(
      isMeshQuery(
        decodeDnsMessage(
          encodeMeshAnnouncement({
            fingerprintHex: FP,
            port: 7788,
            addresses: [],
          })
        )
      )
    );
  });

  it('round-trips an announcement into a peer', () => {
    const bytes = encodeMeshAnnouncement({
      fingerprintHex: FP,
      port: 7788,
      addresses: ['192.168.1.20', 'fe80::1c2d:3e4f:5a6b:7c8d%wlan0'],
    });
    const message = decodeDnsMessage(bytes);
    assert.isTrue(message.isResponse);
    assert.lengthOf(message.questions, 0);
    assert.lengthOf(message.answers, 3);
    assert.lengthOf(message.additionals, 2);

    const [ptr, srv, txt] = message.answers;
    assert.strictEqual(ptr?.type, 'PTR');
    if (ptr?.type === 'PTR') {
      assert.strictEqual(ptr.name, MESH_SERVICE_NAME);
      assert.strictEqual(ptr.ptr, meshInstanceName(FP));
      assert.isFalse(ptr.cacheFlush, 'shared PTR record must not flush');
      assert.strictEqual(ptr.ttl, 120);
    }
    assert.strictEqual(srv?.type, 'SRV');
    if (srv?.type === 'SRV') {
      assert.strictEqual(srv.port, 7788);
      assert.strictEqual(srv.target, meshHostName(FP));
      assert.isTrue(srv.cacheFlush);
    }
    assert.strictEqual(txt?.type, 'TXT');
    if (txt?.type === 'TXT') {
      assert.deepEqual(txt.entries, [`fp=${FP}`]);
    }
    const [a, aaaa] = message.additionals;
    assert.strictEqual(a?.type, 'A');
    if (a?.type === 'A') {
      assert.strictEqual(a.address, '192.168.1.20');
    }
    assert.strictEqual(aaaa?.type, 'AAAA');
    if (aaaa?.type === 'AAAA') {
      assert.strictEqual(aaaa.address, 'fe80::1c2d:3e4f:5a6b:7c8d');
    }

    const peers = parseMeshPeers(message);
    assert.deepEqual(peers, [
      {
        fingerprintHex: FP,
        port: 7788,
        host: meshHostName(FP),
        addresses: ['192.168.1.20', 'fe80::1c2d:3e4f:5a6b:7c8d'],
      },
    ]);
  });

  it('ignores goodbyes (TTL 0), queries and unrelated services', () => {
    const goodbye = decodeDnsMessage(
      encodeMeshAnnouncement({
        fingerprintHex: FP,
        port: 7788,
        addresses: [],
        ttl: 0,
      })
    );
    assert.lengthOf(parseMeshPeers(goodbye), 0);
    assert.lengthOf(parseMeshPeers(decodeDnsMessage(encodeMeshQuery())), 0);

    const other = decodeDnsMessage(
      encodeDnsMessage({
        isResponse: true,
        answers: [
          {
            name: '_http._tcp.local',
            ttl: 120,
            cacheFlush: false,
            type: 'PTR',
            ptr: 'printer._http._tcp.local',
          },
          {
            name: 'printer._http._tcp.local',
            ttl: 120,
            cacheFlush: true,
            type: 'SRV',
            priority: 0,
            weight: 0,
            port: 80,
            target: 'printer.local',
          },
        ],
      })
    );
    assert.lengthOf(parseMeshPeers(other), 0);
  });

  it('takes the fingerprint from TXT even when the instance was renamed', () => {
    // A resolver (or a peer platform) that renamed a colliding instance keeps
    // the TXT record; SRV + TXT without PTR still identify the peer.
    const message = decodeDnsMessage(
      encodeDnsMessage({
        isResponse: true,
        answers: [
          {
            name: `Laptop (2).${MESH_SERVICE_NAME}`,
            ttl: 60,
            cacheFlush: true,
            type: 'SRV',
            priority: 0,
            weight: 0,
            port: 4000,
            target: 'laptop.local',
          },
          {
            name: `Laptop (2).${MESH_SERVICE_NAME}`,
            ttl: 60,
            cacheFlush: true,
            type: 'TXT',
            entries: ['txtvers=1', `fp=${OTHER_FP.toUpperCase()}`],
          },
          {
            name: 'laptop.local',
            ttl: 60,
            cacheFlush: true,
            type: 'A',
            address: '10.0.0.7',
          },
        ],
      })
    );
    const peers = parseMeshPeers(message);
    assert.lengthOf(peers, 1);
    assert.strictEqual(peers[0]?.fingerprintHex, OTHER_FP);
    assert.strictEqual(peers[0]?.port, 4000);
    assert.deepEqual(peers[0]?.addresses, ['10.0.0.7']);
  });

  it('decodes compressed names (pointers) as other responders emit them', () => {
    // Hand-built response: PTR "_asher-mesh._tcp.local" -> "<fp>" + pointer
    // to the service name, and an SRV whose target uses a pointer too.
    const bytes: Array<number> = [];
    const u16 = (v: number) => bytes.push(v >> 8, v & 0xff);
    const u32 = (v: number) => {
      u16(v >>> 16);
      u16(v & 0xffff);
    };
    const label = (s: string) => {
      bytes.push(s.length);
      for (const ch of s) {
        bytes.push(ch.charCodeAt(0));
      }
    };
    u16(0); // id
    u16(0x8400); // response, authoritative
    u16(0); // qd
    u16(2); // an
    u16(0); // ns
    u16(0); // ar
    const serviceOffset = bytes.length;
    label('_asher-mesh');
    label('_tcp');
    label('local');
    bytes.push(0);
    u16(DnsType.PTR);
    u16(1);
    u32(120);
    const rdlenAt = bytes.length;
    u16(0); // patched below
    const rdataStart = bytes.length;
    const instanceOffset = bytes.length;
    label(FP);
    bytes.push(0xc0 | (serviceOffset >> 8), serviceOffset & 0xff);
    const rdlen = bytes.length - rdataStart;
    bytes[rdlenAt] = rdlen >> 8;
    bytes[rdlenAt + 1] = rdlen & 0xff;
    // SRV for the instance (name by pointer).
    bytes.push(0xc0 | (instanceOffset >> 8), instanceOffset & 0xff);
    u16(DnsType.SRV);
    u16(0x8001);
    u32(120);
    u16(6 + 1 + 5 + 2); // priority, weight, port + "asher" + pointer to "local"
    u16(0);
    u16(0);
    u16(7788);
    label('asher');
    const localOffset =
      serviceOffset + 1 + '_asher-mesh'.length + 1 + '_tcp'.length;
    bytes.push(0xc0 | (localOffset >> 8), localOffset & 0xff);

    const message = decodeDnsMessage(Uint8Array.from(bytes));
    assert.strictEqual(message.answers[0]?.type, 'PTR');
    if (message.answers[0]?.type === 'PTR') {
      assert.strictEqual(message.answers[0].ptr, meshInstanceName(FP));
    }
    assert.strictEqual(message.answers[1]?.type, 'SRV');
    if (message.answers[1]?.type === 'SRV') {
      assert.strictEqual(message.answers[1].name, meshInstanceName(FP));
      assert.strictEqual(message.answers[1].target, 'asher.local');
    }
    const peers = parseMeshPeers(message);
    assert.lengthOf(peers, 1);
    assert.strictEqual(peers[0]?.fingerprintHex, FP);
    assert.strictEqual(peers[0]?.host, 'asher.local');
    assert.lengthOf(peers[0]?.addresses ?? [1], 0);
  });

  it('rejects malformed packets instead of looping or reading past the end', () => {
    assert.throws(() => decodeDnsMessage(new Uint8Array(5)), MdnsError);
    // Header promising a question that is not there.
    assert.throws(
      () =>
        decodeDnsMessage(Uint8Array.from([0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0])),
      MdnsError
    );
    // Pointer loop: name at offset 12 points at itself.
    assert.throws(
      () =>
        decodeDnsMessage(
          Uint8Array.from([
            0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0xc0, 12, 0, 12, 0, 1,
          ])
        ),
      MdnsError
    );
    // Label longer than the packet.
    assert.throws(
      () =>
        decodeDnsMessage(
          Uint8Array.from([0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 60, 97, 98])
        ),
      MdnsError
    );
    assert.throws(
      () =>
        encodeMeshAnnouncement({
          fingerprintHex: 'nope',
          port: 1,
          addresses: [],
        }),
      MdnsError
    );
    assert.throws(
      () =>
        encodeMeshAnnouncement({
          fingerprintHex: FP,
          port: 70000,
          addresses: [],
        }),
      MdnsError
    );
    assert.throws(
      () =>
        encodeMeshAnnouncement({
          fingerprintHex: FP,
          port: 1,
          addresses: ['999.1.1.1'],
        }),
      MdnsError
    );
  });

  it('parses addresses', () => {
    assert.deepEqual(Array.from(ipv4ToBytes('10.1.2.3')), [10, 1, 2, 3]);
    assert.deepEqual(
      Array.from(ipv6ToBytes('::1')),
      [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1]
    );
    assert.deepEqual(
      Array.from(ipv6ToBytes('2001:db8::8:800:200c:417a')),
      [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 8, 8, 0, 0x20, 0x0c, 0x41, 0x7a]
    );
    assert.throws(() => ipv6ToBytes('1::2::3'), MdnsError);
    assert.throws(() => ipv6ToBytes('1:2:3'), MdnsError);
  });
});
