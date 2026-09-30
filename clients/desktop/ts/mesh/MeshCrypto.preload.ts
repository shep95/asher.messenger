// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// Signal Protocol for mesh contacts, over the app's own protocol stores so a
// mesh session and an internet session with the same identity are one
// ratchet. The node runs in external-crypto mode and never sees keys:
//
//   send:    MeshNode_PrepareText -> encrypt() here -> MeshNode_SendCiphertext
//   receive: Event.Ciphertext -> decrypt() here -> MeshNode_DeliverPlaintext,
//            or MeshNode_Defer when there is no session yet.
//
// Sessions, identity keys and the contact's prekeys live under
// `ProtocolAddress(meshServiceId, 1)` where meshServiceId is the fingerprint
// rendered as in ts/mesh/address.std.ts (Desktop's stores key by
// ServiceIdString; the crate's own `MeshContactCard_AddressName` hex form is
// only used by the in-crate store, which is not in play here).

import {
  ErrorCode,
  LibSignalErrorBase,
  PreKeyBundle,
  PreKeySignalMessage,
  ProtocolAddress,
  SignalMessage,
  processPreKeyBundle,
  signalDecrypt,
  signalDecryptPreKey,
  signalEncrypt,
} from '@signalapp/libsignal-client';

import {
  IdentityKeys,
  KyberPreKeys,
  PreKeys,
  Sessions,
  SignedPreKeys,
} from '../LibSignalStores.node.ts';
import { createLogger } from '../logging/log.std.ts';
import { signalProtocolStore } from '../SignalProtocolStore.preload.ts';
import { Address } from '../types/Address.std.ts';
import { QualifiedAddress } from '../types/QualifiedAddress.std.ts';
import { meshProtocolAddressParts } from './address.std.ts';
import {
  MESH_MESSAGE_TYPE_PREKEY,
  MESH_MESSAGE_TYPE_WHISPER,
} from './constants.std.ts';
import { NativeMeshContactCard } from './MeshNative.std.ts';

import type { AciString, ServiceIdString } from '../types/ServiceId.std.ts';

const log = createLogger('MeshCrypto');

export type MeshCiphertext = Readonly<{
  /** libsignal ciphertext type: 3 PreKeySignalMessage, 2 SignalMessage. */
  messageType: number;
  ciphertext: Uint8Array;
}>;

export type MeshDecryptResult =
  | { kind: 'plaintext'; plaintext: Uint8Array }
  | { kind: 'defer'; reason: string }
  | { kind: 'drop'; reason: string };

export class MeshCrypto {
  readonly #ourAci: AciString;
  readonly #ourDeviceId: number;
  readonly #sessions: Sessions;
  readonly #identityKeys: IdentityKeys;
  readonly #preKeys: PreKeys;
  readonly #signedPreKeys: SignedPreKeys;
  readonly #kyberPreKeys: KyberPreKeys;

  constructor(ourAci: AciString, ourDeviceId: number) {
    this.#ourAci = ourAci;
    this.#ourDeviceId = ourDeviceId;
    const options = { signalProtocolStore, ourServiceId: ourAci };
    this.#sessions = new Sessions(options);
    this.#identityKeys = new IdentityKeys(options);
    this.#preKeys = new PreKeys(options);
    this.#signedPreKeys = new SignedPreKeys(options);
    this.#kyberPreKeys = new KyberPreKeys(options);
  }

  #addresses(fingerprint: Uint8Array): {
    remote: ProtocolAddress;
    local: ProtocolAddress;
    qualified: QualifiedAddress;
  } {
    const { name, deviceId } = meshProtocolAddressParts(fingerprint);
    return {
      remote: ProtocolAddress.new(name, deviceId),
      local: ProtocolAddress.new(this.#ourAci, this.#ourDeviceId),
      qualified: new QualifiedAddress(
        this.#ourAci,
        Address.create(name as ServiceIdString, deviceId)
      ),
    };
  }

  /**
   * Encrypts a prepared plaintext for `fingerprint`, starting the session
   * from the card's prekey bundle when there is none. `card` may be empty
   * when we only know the peer from their own first message; then an
   * existing session is required.
   */
  async encrypt(
    fingerprint: Uint8Array,
    card: Uint8Array,
    plaintext: Uint8Array
  ): Promise<MeshCiphertext> {
    const { remote, local, qualified } = this.#addresses(fingerprint);
    return signalProtocolStore.enqueueSessionJob(qualified, async () => {
      const session = await this.#sessions.getSession(remote);
      if (!session || !session.hasCurrentState()) {
        if (card.length === 0) {
          throw new Error('MeshCrypto.encrypt: no session and no contact card');
        }
        const bundle = PreKeyBundle._fromNativeHandle(
          NativeMeshContactCard.decode(card).preKeyBundleHandle() as unknown as Parameters<
            typeof PreKeyBundle._fromNativeHandle
          >[0]
        );
        await processPreKeyBundle(
          bundle,
          remote,
          local,
          this.#sessions,
          this.#identityKeys
        );
        log.info(`started session with ${remote.name()}`);
      }
      const message = await signalEncrypt(
        plaintext as Uint8Array<ArrayBuffer>,
        remote,
        local,
        this.#sessions,
        this.#identityKeys
      );
      return {
        messageType: message.type(),
        ciphertext: new Uint8Array(message.serialize()),
      };
    });
  }

  /** Decrypts an incoming mesh ciphertext from `fingerprint`. */
  async decrypt(
    fingerprint: Uint8Array,
    messageType: number,
    ciphertext: Uint8Array
  ): Promise<MeshDecryptResult> {
    const { remote, local, qualified } = this.#addresses(fingerprint);
    const bytes = new Uint8Array(ciphertext) as Uint8Array<ArrayBuffer>;
    try {
      const plaintext = await signalProtocolStore.enqueueSessionJob(
        qualified,
        async () => {
          if (messageType === MESH_MESSAGE_TYPE_PREKEY) {
            return signalDecryptPreKey(
              PreKeySignalMessage.deserialize(bytes),
              remote,
              local,
              this.#sessions,
              this.#identityKeys,
              this.#preKeys,
              this.#signedPreKeys,
              this.#kyberPreKeys
            );
          }
          if (messageType === MESH_MESSAGE_TYPE_WHISPER) {
            return signalDecrypt(
              SignalMessage.deserialize(bytes),
              remote,
              local,
              this.#sessions,
              this.#identityKeys
            );
          }
          throw new Error(`unsupported mesh message type ${messageType}`);
        }
      );
      return { kind: 'plaintext', plaintext: new Uint8Array(plaintext) };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      if (
        error instanceof LibSignalErrorBase &&
        error.code === ErrorCode.InvalidSession
      ) {
        // No (usable) session yet: the PreKeySignalMessage that opens it may
        // still be on its way over another path. Keep the bundle.
        return { kind: 'defer', reason: message };
      }
      if (/session/i.test(message) && /not found|no session/i.test(message)) {
        return { kind: 'defer', reason: message };
      }
      log.warn(`decrypt from ${remote.name()} failed: ${message}`);
      return { kind: 'drop', reason: message };
    }
  }
}
