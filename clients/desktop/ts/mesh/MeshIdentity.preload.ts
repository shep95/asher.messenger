// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// The device's mesh identity: the app's ACI identity key pair and registration
// id wrapped by `MeshIdentity_FromIdentityKeyPair`, so the mesh fingerprint
// is this device's Signal identity. The export blob is kept in the item store
// (`meshIdentity`) so the signed and Kyber prekeys on the contact card stay
// the same across restarts; both records are installed in the ACI prekey
// stores under their ids so a peer's first PreKeySignalMessage decrypts.

import {
  IdentityKeyPair,
  KyberPreKeyRecord,
  SignedPreKeyRecord,
} from '@signalapp/libsignal-client';

import { createLogger } from '../logging/log.std.ts';
import { signalProtocolStore } from '../SignalProtocolStore.preload.ts';
import { itemStorage } from '../textsecure/Storage.preload.ts';
import { strictAssert } from '../util/assert.std.ts';
import { NativeMeshIdentity } from './MeshNative.std.ts';

import type { AciString } from '../types/ServiceId.std.ts';

const log = createLogger('MeshIdentity');

/** `identity::MAX_NAME_BYTES` in the crate. */
const MAX_NAME_BYTES = 64;

function truncateToBytes(value: string, maxBytes: number): string {
  const encoder = new TextEncoder();
  let out = value;
  while (encoder.encode(out).length > maxBytes) {
    out = out.slice(0, -1);
  }
  return out;
}

/** The name on our contact card: the profile title, bounded like the crate. */
export function meshDisplayName(): string {
  const title =
    window.ConversationController.getOurConversation()?.getTitle() ?? '';
  return truncateToBytes(title.trim(), MAX_NAME_BYTES);
}

async function installPreKeys(
  identity: NativeMeshIdentity,
  ourAci: AciString
): Promise<void> {
  const signed = SignedPreKeyRecord._fromNativeHandle(
    identity.signedPreKeyRecordHandle() as unknown as Parameters<
      typeof SignedPreKeyRecord._fromNativeHandle
    >[0]
  );
  // Idempotent (createOrUpdate); re-run on every start so the app's own
  // signed-prekey rotation cleanup cannot leave a card pointing at nothing.
  await signalProtocolStore.storeSignedPreKey(
    ourAci,
    signed.id(),
    new IdentityKeyPair(signed.publicKey(), signed.privateKey()),
    true,
    signed.timestamp()
  );

  const kyber = KyberPreKeyRecord._fromNativeHandle(
    identity.kyberPreKeyRecordHandle() as unknown as Parameters<
      typeof KyberPreKeyRecord._fromNativeHandle
    >[0]
  );
  const existing = await signalProtocolStore.loadKyberPreKey(
    ourAci,
    kyber.id()
  );
  if (!existing) {
    // Last resort: the card's Kyber key is reused by every initiator (there is
    // no server handing out one-time keys), so it must not be deleted after
    // its first use the way one-time Kyber prekeys are.
    await signalProtocolStore.storeKyberPreKeys(ourAci, [
      {
        createdAt: kyber.timestamp(),
        data: kyber.serialize(),
        isConfirmed: true,
        isLastResort: true,
        keyId: kyber.id(),
        ourServiceId: ourAci,
      },
    ]);
  }
  log.info(
    `installed mesh prekeys (signed ${signed.id()}, kyber ${kyber.id()})`
  );
}

/**
 * A fresh mesh identity from this device's ACI identity key pair. Its
 * fingerprint is a hash of that key, so it is the same every time; the
 * prekeys on the card are new, which is why the result is persisted.
 */
export async function deriveMeshIdentityFromAci(
  ourAci: AciString
): Promise<NativeMeshIdentity> {
  const keyPair = signalProtocolStore.getIdentityKeyPair(ourAci);
  strictAssert(keyPair, 'deriveMeshIdentityFromAci: no ACI identity key pair');
  const registrationId =
    await signalProtocolStore.getLocalRegistrationId(ourAci);
  strictAssert(registrationId, 'deriveMeshIdentityFromAci: no registration id');
  return NativeMeshIdentity.fromIdentityKeyPair(
    keyPair.serialize(),
    registrationId,
    meshDisplayName()
  );
}

/** True when an identity export is stored (the node has run before). */
export function hasStoredMeshIdentity(): boolean {
  const stored = itemStorage.get('meshIdentity');
  return stored != null && stored.byteLength > 0;
}

/** Replaces the stored identity export (encrypted backup restore). */
export async function storeMeshIdentity(
  identity: NativeMeshIdentity
): Promise<void> {
  await itemStorage.put(
    'meshIdentity',
    new Uint8Array(identity.export()) as Uint8Array<ArrayBuffer>
  );
}

/** Imports the stored identity or derives one from the ACI key pair. */
export async function loadOrCreateMeshIdentity(
  ourAci: AciString
): Promise<NativeMeshIdentity> {
  let identity: NativeMeshIdentity | undefined;

  const stored = itemStorage.get('meshIdentity');
  if (stored && stored.byteLength > 0) {
    try {
      identity = NativeMeshIdentity.import(new Uint8Array(stored));
    } catch (error) {
      log.error(`stored mesh identity is unreadable, regenerating: ${error}`);
    }
  }

  if (!identity) {
    identity = await deriveMeshIdentityFromAci(ourAci);
    const exported = identity.export();
    await itemStorage.put(
      'meshIdentity',
      new Uint8Array(exported) as Uint8Array<ArrayBuffer>
    );
    log.info('created mesh identity from the ACI identity key pair');
  }

  await installPreKeys(identity, ourAci);
  return identity;
}
