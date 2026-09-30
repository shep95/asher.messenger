// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// Typed access to the meshlink bridge functions on libsignal's Native module.
//
// Desktop reaches libsignal internals through `@signalapp/libsignal-client/dist/*`
// (see RemoteConfig.dom.ts, AccountKeys imports). The meshlink functions are
// generated into that module by the Rust bridge
// (libs/libsignal/rust/bridge/shared/src/mesh.rs) but are not present in every
// published libsignal build, so they are resolved at runtime and a missing
// build fails with one clear error instead of an undefined call.
//
// names must match generated Native.d.ts
// (libs/libsignal/node/ts/Native.ts is the generated source in this monorepo).

import * as LibsignalNative from '@signalapp/libsignal-client/dist/Native.js';

import type { LinkIdType } from './encoding.std.ts';

/** Opaque handles the bridge hands back. Only ever passed straight back in. */
export interface MeshIdentityHandle {
  readonly __type: unique symbol;
}
export interface MeshContactCardHandle {
  readonly __type: unique symbol;
}
export interface MeshNodeHandle {
  readonly __type: unique symbol;
}
/**
 * Handles for libsignal's own record types, to be wrapped with the
 * `_fromNativeHandle` statics of `SignedPreKeyRecord`, `KyberPreKeyRecord`
 * and `PreKeyBundle` from `@signalapp/libsignal-client`.
 */
export interface NativeSignedPreKeyRecordHandle {
  readonly __type: unique symbol;
}
export interface NativeKyberPreKeyRecordHandle {
  readonly __type: unique symbol;
}
export interface NativePreKeyBundleHandle {
  readonly __type: unique symbol;
}

/** The bridge takes handle *wrappers*: objects carrying `_nativeHandle`. */
export type Wrapper<T> = Readonly<{ _nativeHandle: T }>;

/** Byte returns may be `Buffer` or `Uint8Array` depending on the build. */
export type NativeBytes = Uint8Array;

export type MeshNativeFunctions = {
  // ---- Identity ----
  MeshIdentity_FromIdentityKeyPair: (
    keyPair: Uint8Array,
    registrationId: number,
    name: string
  ) => MeshIdentityHandle;
  MeshIdentity_Import: (data: Uint8Array) => MeshIdentityHandle;
  MeshIdentity_Export: (identity: Wrapper<MeshIdentityHandle>) => NativeBytes;
  MeshIdentity_Fingerprint: (
    identity: Wrapper<MeshIdentityHandle>
  ) => NativeBytes;
  MeshIdentity_Card: (identity: Wrapper<MeshIdentityHandle>) => NativeBytes;
  MeshIdentity_SignedPreKeyRecord: (
    identity: Wrapper<MeshIdentityHandle>
  ) => NativeSignedPreKeyRecordHandle;
  MeshIdentity_KyberPreKeyRecord: (
    identity: Wrapper<MeshIdentityHandle>
  ) => NativeKyberPreKeyRecordHandle;

  // ---- Contact cards ----
  MeshContactCard_Decode: (data: Uint8Array) => MeshContactCardHandle;
  MeshContactCard_FromBase64: (text: string) => MeshContactCardHandle;
  MeshContactCard_Encode: (card: Wrapper<MeshContactCardHandle>) => NativeBytes;
  MeshContactCard_ToBase64: (card: Wrapper<MeshContactCardHandle>) => string;
  MeshContactCard_Fingerprint: (
    card: Wrapper<MeshContactCardHandle>
  ) => NativeBytes;
  MeshContactCard_Name: (card: Wrapper<MeshContactCardHandle>) => string;
  MeshContactCard_CreatedAt: (
    card: Wrapper<MeshContactCardHandle>
  ) => bigint | number;
  MeshContactCard_PreKeyBundle: (
    card: Wrapper<MeshContactCardHandle>
  ) => NativePreKeyBundleHandle;
  MeshContactCard_SafetyNumber: (
    mine: Wrapper<MeshContactCardHandle>,
    theirs: Wrapper<MeshContactCardHandle>
  ) => string;

  // ---- Node ----
  MeshNode_New: (
    identity: Wrapper<MeshIdentityHandle>,
    statePath: string | null,
    externalCrypto: boolean,
    antiEntropySecs: number
  ) => MeshNodeHandle;
  MeshNode_Fingerprint: (node: Wrapper<MeshNodeHandle>) => NativeBytes;
  MeshNode_Card: (node: Wrapper<MeshNodeHandle>) => NativeBytes;
  MeshNode_AttachLink: (
    node: Wrapper<MeshNodeHandle>,
    mtu: number,
    maxBytesPerSec: number,
    maxFramesPerSec: number
  ) => LinkIdType;
  MeshNode_DetachLink: (node: Wrapper<MeshNodeHandle>, link: LinkIdType) => void;
  MeshNode_LinkWrite: (
    node: Wrapper<MeshNodeHandle>,
    link: LinkIdType,
    frame: Uint8Array
  ) => boolean;
  MeshNode_LinkRead: (
    node: Wrapper<MeshNodeHandle>,
    link: LinkIdType,
    timeoutMs: number
  ) => NativeBytes;
  MeshNode_NextEvent: (
    node: Wrapper<MeshNodeHandle>,
    timeoutMs: number
  ) => NativeBytes;
  MeshNode_AddContact: (
    node: Wrapper<MeshNodeHandle>,
    card: Uint8Array
  ) => NativeBytes;
  MeshNode_RemoveContact: (
    node: Wrapper<MeshNodeHandle>,
    fingerprint: Uint8Array
  ) => boolean;
  MeshNode_Contact: (
    node: Wrapper<MeshNodeHandle>,
    fingerprint: Uint8Array
  ) => NativeBytes;
  MeshNode_Contacts: (node: Wrapper<MeshNodeHandle>) => Array<NativeBytes>;
  MeshNode_BroadcastCard: (node: Wrapper<MeshNodeHandle>) => NativeBytes;
  MeshNode_PrepareText: (
    node: Wrapper<MeshNodeHandle>,
    to: Uint8Array,
    plaintext: Uint8Array
  ) => NativeBytes;
  MeshNode_SendCiphertext: (
    node: Wrapper<MeshNodeHandle>,
    to: Uint8Array,
    commit: Uint8Array,
    messageType: number,
    ciphertext: Uint8Array
  ) => NativeBytes;
  MeshNode_DeliverPlaintext: (
    node: Wrapper<MeshNodeHandle>,
    bundleId: Uint8Array,
    plaintext: Uint8Array
  ) => void;
  MeshNode_Defer: (node: Wrapper<MeshNodeHandle>, bundleId: Uint8Array) => void;
  MeshNode_Stats: (node: Wrapper<MeshNodeHandle>) => NativeBytes;
  MeshNode_Flush: (node: Wrapper<MeshNodeHandle>) => void;
};

const REQUIRED_FUNCTIONS: ReadonlyArray<keyof MeshNativeFunctions> = [
  'MeshIdentity_FromIdentityKeyPair',
  'MeshIdentity_Import',
  'MeshIdentity_Export',
  'MeshIdentity_Fingerprint',
  'MeshIdentity_Card',
  'MeshIdentity_SignedPreKeyRecord',
  'MeshIdentity_KyberPreKeyRecord',
  'MeshContactCard_Decode',
  'MeshContactCard_FromBase64',
  'MeshContactCard_Encode',
  'MeshContactCard_ToBase64',
  'MeshContactCard_Fingerprint',
  'MeshContactCard_Name',
  'MeshContactCard_CreatedAt',
  'MeshContactCard_PreKeyBundle',
  'MeshContactCard_SafetyNumber',
  'MeshNode_New',
  'MeshNode_Fingerprint',
  'MeshNode_Card',
  'MeshNode_AttachLink',
  'MeshNode_DetachLink',
  'MeshNode_LinkWrite',
  'MeshNode_LinkRead',
  'MeshNode_NextEvent',
  'MeshNode_AddContact',
  'MeshNode_RemoveContact',
  'MeshNode_Contact',
  'MeshNode_Contacts',
  'MeshNode_BroadcastCard',
  'MeshNode_PrepareText',
  'MeshNode_SendCiphertext',
  'MeshNode_DeliverPlaintext',
  'MeshNode_Defer',
  'MeshNode_Stats',
  'MeshNode_Flush',
];

let resolved: MeshNativeFunctions | undefined;

function missingFunctions(): Array<string> {
  const candidate = LibsignalNative as unknown as Record<string, unknown>;
  return REQUIRED_FUNCTIONS.filter(
    name => typeof candidate[name] !== 'function'
  );
}

/** True when the bundled libsignal build carries meshlink. */
export function isMeshNativeAvailable(): boolean {
  return resolved != null || missingFunctions().length === 0;
}

/** The bridge functions, or a clear error naming what the build lacks. */
export function getMeshNative(): MeshNativeFunctions {
  if (resolved) {
    return resolved;
  }
  const missing = missingFunctions();
  if (missing.length > 0) {
    throw new Error(
      'This libsignal build does not include meshlink ' +
        `(missing Native.${missing.slice(0, 3).join(', Native.')}` +
        `${missing.length > 3 ? `, +${missing.length - 3} more` : ''})`
    );
  }
  resolved = LibsignalNative as unknown as MeshNativeFunctions;
  return resolved;
}

/** Copies bridge bytes (which may be a pooled Buffer) into a plain array. */
export function toBytes(value: NativeBytes): Uint8Array {
  return new Uint8Array(value);
}

/** Wrapper around a `MeshIdentity` handle. */
export class NativeMeshIdentity {
  readonly _nativeHandle: MeshIdentityHandle;

  private constructor(handle: MeshIdentityHandle) {
    this._nativeHandle = handle;
  }

  static fromIdentityKeyPair(
    serializedKeyPair: Uint8Array,
    registrationId: number,
    name: string
  ): NativeMeshIdentity {
    return new NativeMeshIdentity(
      getMeshNative().MeshIdentity_FromIdentityKeyPair(
        serializedKeyPair,
        registrationId,
        name
      )
    );
  }

  static import(data: Uint8Array): NativeMeshIdentity {
    return new NativeMeshIdentity(getMeshNative().MeshIdentity_Import(data));
  }

  export(): Uint8Array {
    return toBytes(getMeshNative().MeshIdentity_Export(this));
  }

  fingerprint(): Uint8Array {
    return toBytes(getMeshNative().MeshIdentity_Fingerprint(this));
  }

  card(): Uint8Array {
    return toBytes(getMeshNative().MeshIdentity_Card(this));
  }

  signedPreKeyRecordHandle(): NativeSignedPreKeyRecordHandle {
    return getMeshNative().MeshIdentity_SignedPreKeyRecord(this);
  }

  kyberPreKeyRecordHandle(): NativeKyberPreKeyRecordHandle {
    return getMeshNative().MeshIdentity_KyberPreKeyRecord(this);
  }
}

/** Wrapper around a decoded, signature-checked `ContactCard`. */
export class NativeMeshContactCard {
  readonly _nativeHandle: MeshContactCardHandle;

  private constructor(handle: MeshContactCardHandle) {
    this._nativeHandle = handle;
  }

  static decode(data: Uint8Array): NativeMeshContactCard {
    return new NativeMeshContactCard(getMeshNative().MeshContactCard_Decode(data));
  }

  static fromBase64(text: string): NativeMeshContactCard {
    return new NativeMeshContactCard(
      getMeshNative().MeshContactCard_FromBase64(text)
    );
  }

  encode(): Uint8Array {
    return toBytes(getMeshNative().MeshContactCard_Encode(this));
  }

  toBase64(): string {
    return getMeshNative().MeshContactCard_ToBase64(this);
  }

  fingerprint(): Uint8Array {
    return toBytes(getMeshNative().MeshContactCard_Fingerprint(this));
  }

  name(): string {
    return getMeshNative().MeshContactCard_Name(this);
  }

  /** Seconds since the epoch. */
  createdAt(): number {
    return Number(getMeshNative().MeshContactCard_CreatedAt(this));
  }

  preKeyBundleHandle(): NativePreKeyBundleHandle {
    return getMeshNative().MeshContactCard_PreKeyBundle(this);
  }

  safetyNumberWith(theirs: NativeMeshContactCard): string {
    return getMeshNative().MeshContactCard_SafetyNumber(this, theirs);
  }
}

/** Wrapper around a running node. All calls are synchronous bridge calls. */
export class NativeMeshNode {
  readonly _nativeHandle: MeshNodeHandle;

  private constructor(handle: MeshNodeHandle) {
    this._nativeHandle = handle;
  }

  static new(
    identity: NativeMeshIdentity,
    statePath: string | null,
    externalCrypto: boolean,
    antiEntropySecs: number
  ): NativeMeshNode {
    return new NativeMeshNode(
      getMeshNative().MeshNode_New(
        identity,
        statePath,
        externalCrypto,
        antiEntropySecs
      )
    );
  }

  fingerprint(): Uint8Array {
    return toBytes(getMeshNative().MeshNode_Fingerprint(this));
  }

  card(): Uint8Array {
    return toBytes(getMeshNative().MeshNode_Card(this));
  }

  attachLink(
    mtu: number,
    maxBytesPerSec: number,
    maxFramesPerSec: number
  ): LinkIdType {
    return getMeshNative().MeshNode_AttachLink(
      this,
      mtu,
      maxBytesPerSec,
      maxFramesPerSec
    );
  }

  detachLink(link: LinkIdType): void {
    getMeshNative().MeshNode_DetachLink(this, link);
  }

  /** A frame received from the wire. False: link gone or back-pressure. */
  linkWrite(link: LinkIdType, frame: Uint8Array): boolean {
    return getMeshNative().MeshNode_LinkWrite(this, link, frame);
  }

  /** The next frame to transmit, or empty when nothing is ready. */
  linkRead(link: LinkIdType, timeoutMs: number): Uint8Array {
    return toBytes(getMeshNative().MeshNode_LinkRead(this, link, timeoutMs));
  }

  /** The next encoded event, or empty when nothing is ready. */
  nextEvent(timeoutMs: number): Uint8Array {
    return toBytes(getMeshNative().MeshNode_NextEvent(this, timeoutMs));
  }

  addContact(card: Uint8Array): Uint8Array {
    return toBytes(getMeshNative().MeshNode_AddContact(this, card));
  }

  removeContact(fingerprint: Uint8Array): boolean {
    return getMeshNative().MeshNode_RemoveContact(this, fingerprint);
  }

  /** The contact's encoded card, or empty if unknown. */
  contact(fingerprint: Uint8Array): Uint8Array {
    return toBytes(getMeshNative().MeshNode_Contact(this, fingerprint));
  }

  contacts(): Array<Uint8Array> {
    return getMeshNative().MeshNode_Contacts(this).map(toBytes);
  }

  broadcastCard(): Uint8Array {
    return toBytes(getMeshNative().MeshNode_BroadcastCard(this));
  }

  /** External-crypto mode: an encoded prepared list with one item. */
  prepareText(to: Uint8Array, plaintext: Uint8Array): Uint8Array {
    return toBytes(getMeshNative().MeshNode_PrepareText(this, to, plaintext));
  }

  /** External-crypto mode. Returns the bundle id. */
  sendCiphertext(
    to: Uint8Array,
    commit: Uint8Array,
    messageType: number,
    ciphertext: Uint8Array
  ): Uint8Array {
    return toBytes(
      getMeshNative().MeshNode_SendCiphertext(
        this,
        to,
        commit,
        messageType,
        ciphertext
      )
    );
  }

  deliverPlaintext(bundleId: Uint8Array, plaintext: Uint8Array): void {
    getMeshNative().MeshNode_DeliverPlaintext(this, bundleId, plaintext);
  }

  defer(bundleId: Uint8Array): void {
    getMeshNative().MeshNode_Defer(this, bundleId);
  }

  stats(): Uint8Array {
    return toBytes(getMeshNative().MeshNode_Stats(this));
  }

  flush(): void {
    getMeshNative().MeshNode_Flush(this);
  }
}
