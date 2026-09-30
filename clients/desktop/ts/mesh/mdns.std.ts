// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// oxlint-disable max-classes-per-file, no-bitwise -- a wire codec: a writer
// and a reader over the same buffer, built from shifts and masks.

// A minimal mDNS / DNS-SD codec (RFC 1035 messages, RFC 6762 mDNS bits, RFC
// 6763 service records) for LAN discovery of mesh peers without a native
// dependency. The main process (app/mesh_channel.main.ts) puts these packets
// on 224.0.0.251:5353 with Node's `dgram`; this module only turns bytes into
// records and back so it can be unit-tested (ts/test-node/mesh/mdns_test).
//
// What we advertise, and what Android's NsdManager / iOS Bonjour see:
//
//   _asher-mesh._tcp.local           PTR  <fp>._asher-mesh._tcp.local
//   <fp>._asher-mesh._tcp.local      SRV  0 0 <port> asher-<fp12>.local
//   <fp>._asher-mesh._tcp.local      TXT  "fp=<fingerprint hex>"
//   asher-<fp12>.local               A / AAAA  <each LAN address>
//
// The instance label is the 32-hex fingerprint (unique, under the 63-byte
// label limit) and the TXT record repeats it so a resolver that renames
// colliding instances still yields the right identity.

import {
  MESH_MDNS_DOMAIN,
  MESH_MDNS_SERVICE_TYPE,
  MESH_MDNS_TTL_SECS,
} from './constants.std.ts';

export const DnsType = {
  A: 1,
  PTR: 12,
  TXT: 16,
  AAAA: 28,
  SRV: 33,
  ANY: 255,
} as const;

export const DNS_CLASS_IN = 1;
/** Top class bit: cache-flush on records, unicast-response on questions. */
const MDNS_TOP_BIT = 0x8000;
const FLAG_RESPONSE = 0x8000;
const FLAG_AUTHORITATIVE = 0x0400;
const MAX_NAME_LENGTH = 255;
const MAX_LABEL_LENGTH = 63;
/** Bytes; conservative for an unfragmented UDP datagram on any LAN. */
const MAX_MESSAGE_LENGTH = 9000;

export const MESH_SERVICE_NAME = `${MESH_MDNS_SERVICE_TYPE}.${MESH_MDNS_DOMAIN}`;

export class MdnsError extends Error {
  constructor(message: string) {
    super(`mdns: ${message}`);
    this.name = 'MdnsError';
  }
}

export type DnsQuestion = Readonly<{
  name: string;
  type: number;
  unicastResponse: boolean;
}>;

export type DnsRecord = Readonly<
  { name: string; ttl: number; cacheFlush: boolean } & (
    | { type: 'PTR'; ptr: string }
    | {
        type: 'SRV';
        priority: number;
        weight: number;
        port: number;
        target: string;
      }
    | { type: 'TXT'; entries: ReadonlyArray<string> }
    | { type: 'A'; address: string }
    | { type: 'AAAA'; address: string }
    | { type: 'other'; rawType: number; data: Uint8Array }
  )
>;

export type DnsMessage = Readonly<{
  id: number;
  isResponse: boolean;
  questions: ReadonlyArray<DnsQuestion>;
  answers: ReadonlyArray<DnsRecord>;
  authorities: ReadonlyArray<DnsRecord>;
  additionals: ReadonlyArray<DnsRecord>;
}>;

// ---- names ------------------------------------------------------------------

/** DNS names are case-insensitive; everything is compared lower-cased. */
export function namesEqual(a: string, b: string): boolean {
  return normalizeName(a) === normalizeName(b);
}

function normalizeName(name: string): string {
  return name.replace(/\.$/, '').toLowerCase();
}

export function meshInstanceName(fingerprintHex: string): string {
  return `${fingerprintHex.toLowerCase()}.${MESH_SERVICE_NAME}`;
}

export function meshHostName(fingerprintHex: string): string {
  return `asher-${fingerprintHex.toLowerCase().slice(0, 12)}.${MESH_MDNS_DOMAIN}`;
}

// ---- writer -----------------------------------------------------------------

class Writer {
  readonly #bytes: Array<number> = [];

  get length(): number {
    return this.#bytes.length;
  }

  u8(v: number): this {
    this.#bytes.push(v & 0xff);
    return this;
  }

  u16(v: number): this {
    return this.u8(v >>> 8).u8(v);
  }

  u32(v: number): this {
    return this.u16(v >>> 16).u16(v & 0xffff);
  }

  raw(v: Uint8Array): this {
    for (const b of v) {
      this.#bytes.push(b);
    }
    return this;
  }

  /** Uncompressed name; every label is checked against the RFC limits. */
  name(name: string): this {
    const encoder = new TextEncoder();
    const labels = normalizeName(name).split('.').filter(Boolean);
    let total = 1;
    for (const label of labels) {
      const bytes = encoder.encode(label);
      if (bytes.length === 0 || bytes.length > MAX_LABEL_LENGTH) {
        throw new MdnsError(`label out of range in ${name}`);
      }
      total += 1 + bytes.length;
      this.u8(bytes.length).raw(bytes);
    }
    if (total > MAX_NAME_LENGTH) {
      throw new MdnsError(`name too long: ${name}`);
    }
    return this.u8(0);
  }

  /** Writes the record with the RDATA produced by `body`. */
  record(
    name: string,
    type: number,
    ttl: number,
    cacheFlush: boolean,
    body: (w: Writer) => void
  ): this {
    this.name(name)
      .u16(type)
      .u16(cacheFlush ? DNS_CLASS_IN | MDNS_TOP_BIT : DNS_CLASS_IN)
      .u32(ttl);
    const inner = new Writer();
    body(inner);
    const data = inner.finish();
    if (data.length > 0xffff) {
      throw new MdnsError('rdata too long');
    }
    return this.u16(data.length).raw(data);
  }

  finish(): Uint8Array {
    return Uint8Array.from(this.#bytes);
  }
}

// ---- addresses --------------------------------------------------------------

export function ipv4ToBytes(address: string): Uint8Array {
  const parts = address.split('.');
  if (parts.length !== 4) {
    throw new MdnsError(`not an IPv4 address: ${address}`);
  }
  const out = new Uint8Array(4);
  parts.forEach((part, i) => {
    const n = Number(part);
    if (!/^\d{1,3}$/.test(part) || n > 255) {
      throw new MdnsError(`not an IPv4 address: ${address}`);
    }
    out[i] = n;
  });
  return out;
}

export function ipv6ToBytes(address: string): Uint8Array {
  // Strip a zone id ("fe80::1%eth0"); it is not part of the wire form.
  const [bare] = address.split('%');
  if (bare == null || bare.length === 0) {
    throw new MdnsError(`not an IPv6 address: ${address}`);
  }
  const halves = bare.split('::');
  if (halves.length > 2) {
    throw new MdnsError(`not an IPv6 address: ${address}`);
  }
  const parse = (part: string): Array<number> =>
    part.length === 0
      ? []
      : part.split(':').map(group => {
          if (!/^[0-9a-fA-F]{1,4}$/.test(group)) {
            throw new MdnsError(`not an IPv6 address: ${address}`);
          }
          return parseInt(group, 16);
        });
  const head = parse(halves[0] ?? '');
  const tail = halves.length === 2 ? parse(halves[1] ?? '') : [];
  const missing = 8 - head.length - tail.length;
  if (missing < 0 || (halves.length === 1 && missing !== 0)) {
    throw new MdnsError(`not an IPv6 address: ${address}`);
  }
  const groups = [...head, ...new Array<number>(missing).fill(0), ...tail];
  const out = new Uint8Array(16);
  groups.forEach((g, i) => {
    out[i * 2] = g >>> 8;
    out[i * 2 + 1] = g & 0xff;
  });
  return out;
}

function bytesToIpv4(bytes: Uint8Array): string {
  return Array.from(bytes).join('.');
}

function bytesToIpv6(bytes: Uint8Array): string {
  const groups: Array<string> = [];
  for (let i = 0; i < 16; i += 2) {
    groups.push((((bytes[i] ?? 0) << 8) | (bytes[i + 1] ?? 0)).toString(16));
  }
  // Compress the longest run of zero groups (RFC 5952), as Node prints them.
  let bestStart = -1;
  let bestLen = 0;
  for (let i = 0; i < groups.length; ) {
    if (groups[i] !== '0') {
      i += 1;
      continue;
    }
    let j = i;
    while (j < groups.length && groups[j] === '0') {
      j += 1;
    }
    if (j - i > bestLen) {
      bestStart = i;
      bestLen = j - i;
    }
    i = j;
  }
  if (bestLen < 2) {
    return groups.join(':');
  }
  const left = groups.slice(0, bestStart).join(':');
  const right = groups.slice(bestStart + bestLen).join(':');
  return `${left}::${right}`;
}

export function isIpv4(address: string): boolean {
  return /^\d{1,3}(\.\d{1,3}){3}$/.test(address);
}

// ---- encoding ---------------------------------------------------------------

export type EncodeMessageOptions = Readonly<{
  id?: number;
  isResponse: boolean;
  questions?: ReadonlyArray<DnsQuestion>;
  answers?: ReadonlyArray<DnsRecord>;
  additionals?: ReadonlyArray<DnsRecord>;
}>;

function writeRecord(w: Writer, record: DnsRecord): void {
  switch (record.type) {
    case 'PTR':
      w.record(record.name, DnsType.PTR, record.ttl, record.cacheFlush, inner =>
        inner.name(record.ptr)
      );
      return;
    case 'SRV':
      w.record(record.name, DnsType.SRV, record.ttl, record.cacheFlush, inner =>
        inner
          .u16(record.priority)
          .u16(record.weight)
          .u16(record.port)
          .name(record.target)
      );
      return;
    case 'TXT':
      w.record(
        record.name,
        DnsType.TXT,
        record.ttl,
        record.cacheFlush,
        inner => {
          const encoder = new TextEncoder();
          const entries = record.entries.length > 0 ? record.entries : [''];
          for (const entry of entries) {
            const bytes = encoder.encode(entry);
            if (bytes.length > 255) {
              throw new MdnsError('txt entry too long');
            }
            inner.u8(bytes.length).raw(bytes);
          }
        }
      );
      return;
    case 'A':
      w.record(record.name, DnsType.A, record.ttl, record.cacheFlush, inner =>
        inner.raw(ipv4ToBytes(record.address))
      );
      return;
    case 'AAAA':
      w.record(
        record.name,
        DnsType.AAAA,
        record.ttl,
        record.cacheFlush,
        inner => inner.raw(ipv6ToBytes(record.address))
      );
      return;
    case 'other':
      w.record(
        record.name,
        record.rawType,
        record.ttl,
        record.cacheFlush,
        inner => inner.raw(record.data)
      );
      return;
    default:
      throw new MdnsError('unknown record type');
  }
}

export function encodeDnsMessage(options: EncodeMessageOptions): Uint8Array {
  const questions = options.questions ?? [];
  const answers = options.answers ?? [];
  const additionals = options.additionals ?? [];
  const w = new Writer();
  w.u16(options.id ?? 0)
    .u16(options.isResponse ? FLAG_RESPONSE | FLAG_AUTHORITATIVE : 0)
    .u16(questions.length)
    .u16(answers.length)
    .u16(0)
    .u16(additionals.length);
  for (const q of questions) {
    w.name(q.name)
      .u16(q.type)
      .u16(q.unicastResponse ? DNS_CLASS_IN | MDNS_TOP_BIT : DNS_CLASS_IN);
  }
  for (const record of answers) {
    writeRecord(w, record);
  }
  for (const record of additionals) {
    writeRecord(w, record);
  }
  if (w.length > MAX_MESSAGE_LENGTH) {
    throw new MdnsError(`message too long (${w.length} bytes)`);
  }
  return w.finish();
}

// ---- decoding ---------------------------------------------------------------

class Reader {
  readonly #data: Uint8Array;
  #pos: number;

  constructor(data: Uint8Array, pos = 0) {
    this.#data = data;
    this.#pos = pos;
  }

  get pos(): number {
    return this.#pos;
  }

  /** The whole message, for compression pointers inside RDATA. */
  get buffer(): Uint8Array {
    return this.#data;
  }

  get remaining(): number {
    return this.#data.length - this.#pos;
  }

  #need(n: number): void {
    if (n > this.remaining) {
      throw new MdnsError('truncated message');
    }
  }

  u8(): number {
    this.#need(1);
    const v = this.#data[this.#pos] ?? 0;
    this.#pos += 1;
    return v;
  }

  u16(): number {
    return (this.u8() << 8) | this.u8();
  }

  u32(): number {
    return (this.u16() * 0x10000 + this.u16()) >>> 0;
  }

  raw(n: number): Uint8Array {
    this.#need(n);
    const out = new Uint8Array(this.#data.subarray(this.#pos, this.#pos + n));
    this.#pos += n;
    return out;
  }

  /** A possibly-compressed name; pointers may only point backwards. */
  name(): string {
    const labels: Array<string> = [];
    const decoder = new TextDecoder();
    let pos = this.#pos;
    let end = -1;
    let hops = 0;
    let total = 0;
    for (;;) {
      if (pos >= this.#data.length) {
        throw new MdnsError('truncated name');
      }
      const len = this.#data[pos] ?? 0;
      if ((len & 0xc0) === 0xc0) {
        if (pos + 1 >= this.#data.length) {
          throw new MdnsError('truncated pointer');
        }
        const target = ((len & 0x3f) << 8) | (this.#data[pos + 1] ?? 0);
        if (target >= pos) {
          throw new MdnsError('forward name pointer');
        }
        hops += 1;
        if (hops > 32) {
          throw new MdnsError('pointer loop');
        }
        if (end < 0) {
          end = pos + 2;
        }
        pos = target;
        continue;
      }
      if ((len & 0xc0) !== 0) {
        throw new MdnsError('unsupported label type');
      }
      pos += 1;
      if (len === 0) {
        break;
      }
      if (pos + len > this.#data.length) {
        throw new MdnsError('truncated label');
      }
      total += len + 1;
      if (total > MAX_NAME_LENGTH) {
        throw new MdnsError('name too long');
      }
      labels.push(decoder.decode(this.#data.subarray(pos, pos + len)));
      pos += len;
    }
    this.#pos = end < 0 ? pos : end;
    return labels.join('.');
  }
}

function readRecord(r: Reader): DnsRecord {
  const name = r.name();
  const type = r.u16();
  const klass = r.u16();
  const ttl = r.u32();
  const length = r.u16();
  const cacheFlush = (klass & MDNS_TOP_BIT) !== 0;
  const base = { name, ttl, cacheFlush };
  const start = r.pos;
  const data = r.raw(length);
  // Names inside RDATA may be compressed against the whole message, so they
  // are read from a reader positioned in the full buffer.
  const inner = new Reader(r.buffer, start);
  let record: DnsRecord;
  switch (type) {
    case DnsType.PTR:
      record = { ...base, type: 'PTR', ptr: inner.name() };
      break;
    case DnsType.SRV: {
      const priority = inner.u16();
      const weight = inner.u16();
      const port = inner.u16();
      record = {
        ...base,
        type: 'SRV',
        priority,
        weight,
        port,
        target: inner.name(),
      };
      break;
    }
    case DnsType.TXT: {
      const entries: Array<string> = [];
      const decoder = new TextDecoder();
      const txt = new Reader(data);
      while (txt.remaining > 0) {
        const len = txt.u8();
        entries.push(decoder.decode(txt.raw(len)));
      }
      record = { ...base, type: 'TXT', entries };
      break;
    }
    case DnsType.A:
      if (data.length !== 4) {
        throw new MdnsError('bad A record');
      }
      record = { ...base, type: 'A', address: bytesToIpv4(data) };
      break;
    case DnsType.AAAA:
      if (data.length !== 16) {
        throw new MdnsError('bad AAAA record');
      }
      record = { ...base, type: 'AAAA', address: bytesToIpv6(data) };
      break;
    default:
      record = { ...base, type: 'other', rawType: type, data };
      break;
  }
  return record;
}

export function decodeDnsMessage(bytes: Uint8Array): DnsMessage {
  if (bytes.length < 12) {
    throw new MdnsError('message shorter than a header');
  }
  const r = new Reader(bytes);
  const id = r.u16();
  const flags = r.u16();
  const qd = r.u16();
  const an = r.u16();
  const ns = r.u16();
  const ar = r.u16();
  const questions: Array<DnsQuestion> = [];
  for (let i = 0; i < qd; i += 1) {
    const name = r.name();
    const type = r.u16();
    const klass = r.u16();
    questions.push({
      name,
      type,
      unicastResponse: (klass & MDNS_TOP_BIT) !== 0,
    });
  }
  const readAll = (count: number): Array<DnsRecord> => {
    const out: Array<DnsRecord> = [];
    for (let i = 0; i < count; i += 1) {
      out.push(readRecord(r));
    }
    return out;
  };
  const answers = readAll(an);
  const authorities = readAll(ns);
  const additionals = readAll(ar);
  return {
    id,
    isResponse: (flags & FLAG_RESPONSE) !== 0,
    questions,
    answers,
    authorities,
    additionals,
  };
}

// ---- the mesh service -------------------------------------------------------

export type MeshAnnouncement = Readonly<{
  fingerprintHex: string;
  port: number;
  /** LAN addresses (IPv4 dotted or IPv6) to publish as A / AAAA records. */
  addresses: ReadonlyArray<string>;
  /** Seconds; 0 is the DNS-SD "goodbye" that flushes peers' caches. */
  ttl?: number;
}>;

export type MeshPeer = Readonly<{
  fingerprintHex: string;
  port: number;
  /** SRV target host name (for resolvers that need it). */
  host: string;
  /** Addresses from A/AAAA records in the same message, IPv4 first. */
  addresses: ReadonlyArray<string>;
}>;

const FINGERPRINT_HEX = /^[0-9a-f]{32}$/i;

/** A PTR question for our service, sent when we start and periodically. */
export function encodeMeshQuery(): Uint8Array {
  return encodeDnsMessage({
    isResponse: false,
    questions: [
      { name: MESH_SERVICE_NAME, type: DnsType.PTR, unicastResponse: false },
    ],
  });
}

/** True when the message asks for our service (PTR or ANY). */
export function isMeshQuery(message: DnsMessage): boolean {
  return (
    !message.isResponse &&
    message.questions.some(
      q =>
        (q.type === DnsType.PTR || q.type === DnsType.ANY) &&
        namesEqual(q.name, MESH_SERVICE_NAME)
    )
  );
}

/** Our full DNS-SD record set as one authoritative response. */
export function encodeMeshAnnouncement(a: MeshAnnouncement): Uint8Array {
  if (!FINGERPRINT_HEX.test(a.fingerprintHex)) {
    throw new MdnsError('fingerprint must be 32 hex characters');
  }
  if (!Number.isInteger(a.port) || a.port < 1 || a.port > 65535) {
    throw new MdnsError('port out of range');
  }
  const ttl = a.ttl ?? MESH_MDNS_TTL_SECS;
  const instance = meshInstanceName(a.fingerprintHex);
  const host = meshHostName(a.fingerprintHex);
  const answers: Array<DnsRecord> = [
    {
      name: MESH_SERVICE_NAME,
      ttl,
      cacheFlush: false,
      type: 'PTR',
      ptr: instance,
    },
    {
      name: instance,
      ttl,
      cacheFlush: true,
      type: 'SRV',
      priority: 0,
      weight: 0,
      port: a.port,
      target: host,
    },
    {
      name: instance,
      ttl,
      cacheFlush: true,
      type: 'TXT',
      entries: [`fp=${a.fingerprintHex.toLowerCase()}`],
    },
  ];
  const additionals: Array<DnsRecord> = a.addresses.map(address =>
    isIpv4(address)
      ? { name: host, ttl, cacheFlush: true, type: 'A', address }
      : { name: host, ttl, cacheFlush: true, type: 'AAAA', address }
  );
  return encodeDnsMessage({ isResponse: true, answers, additionals });
}

/**
 * Peers advertised in a response: every instance of our service that has an
 * SRV record and a `fp=` TXT entry (records with TTL 0 are goodbyes and are
 * skipped). Addresses come from A/AAAA records for the SRV target; the caller
 * may also fall back to the datagram's source address.
 */
export function parseMeshPeers(message: DnsMessage): Array<MeshPeer> {
  if (!message.isResponse) {
    return [];
  }
  const records = [
    ...message.answers,
    ...message.authorities,
    ...message.additionals,
  ];
  const instances = new Set<string>();
  for (const record of records) {
    if (
      record.type === 'PTR' &&
      record.ttl > 0 &&
      namesEqual(record.name, MESH_SERVICE_NAME)
    ) {
      instances.add(normalizeName(record.ptr));
    }
  }
  // An SRV/TXT pair for our service type without the PTR still counts.
  for (const record of records) {
    if (
      (record.type === 'SRV' || record.type === 'TXT') &&
      record.ttl > 0 &&
      normalizeName(record.name).endsWith(`.${MESH_SERVICE_NAME}`)
    ) {
      instances.add(normalizeName(record.name));
    }
  }

  const peers: Array<MeshPeer> = [];
  for (const instance of instances) {
    const srv = records.find(
      (r): r is Extract<DnsRecord, { type: 'SRV' }> =>
        r.type === 'SRV' && r.ttl > 0 && namesEqual(r.name, instance)
    );
    const txt = records.find(
      (r): r is Extract<DnsRecord, { type: 'TXT' }> =>
        r.type === 'TXT' && r.ttl > 0 && namesEqual(r.name, instance)
    );
    if (!srv) {
      continue;
    }
    let fingerprintHex: string | undefined;
    for (const entry of txt?.entries ?? []) {
      const match = /^fp=([0-9a-fA-F]{32})$/.exec(entry);
      if (match?.[1]) {
        fingerprintHex = match[1].toLowerCase();
      }
    }
    if (!fingerprintHex) {
      // Fall back to the instance label when it is a fingerprint.
      const label = instance.slice(
        0,
        instance.length - MESH_SERVICE_NAME.length - 1
      );
      if (FINGERPRINT_HEX.test(label)) {
        fingerprintHex = label.toLowerCase();
      }
    }
    if (!fingerprintHex || srv.port < 1) {
      continue;
    }
    const v4: Array<string> = [];
    const v6: Array<string> = [];
    for (const record of records) {
      if (record.ttl > 0 && namesEqual(record.name, srv.target)) {
        if (record.type === 'A') {
          v4.push(record.address);
        } else if (record.type === 'AAAA') {
          v6.push(record.address);
        }
      }
    }
    peers.push({
      fingerprintHex,
      port: srv.port,
      host: srv.target,
      addresses: [...v4, ...v6],
    });
  }
  return peers;
}
