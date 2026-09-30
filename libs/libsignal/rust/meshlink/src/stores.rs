//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! The seam between meshlink and the app's own Signal Protocol storage.
//!
//! libsignal's session functions take the five stores as separate `&mut dyn`
//! references. An app that keeps them in one object (Android, iOS and
//! Desktop all do) implements [`ProtocolStores::parts`] by handing out its
//! fields; the in-memory store used by tests does the same. Mesh sessions
//! then live in the same ratchet state as internet sessions with the same
//! contact.

use libsignal_protocol::{
    IdentityKeyStore, InMemSignalProtocolStore, KyberPreKeyStore, PreKeyStore, SessionStore,
    SignedPreKeyStore,
};

/// Split borrows of the five stores libsignal needs.
pub struct StoreParts<'a> {
    pub session: &'a mut dyn SessionStore,
    pub identity: &'a mut dyn IdentityKeyStore,
    pub pre_key: &'a mut dyn PreKeyStore,
    pub signed_pre_key: &'a mut dyn SignedPreKeyStore,
    pub kyber_pre_key: &'a mut dyn KyberPreKeyStore,
}

/// Anything that can lend meshlink its Signal Protocol stores.
pub trait ProtocolStores: Send {
    fn parts(&mut self) -> StoreParts<'_>;
}

impl ProtocolStores for InMemSignalProtocolStore {
    fn parts(&mut self) -> StoreParts<'_> {
        StoreParts {
            session: &mut self.session_store,
            identity: &mut self.identity_store,
            pre_key: &mut self.pre_key_store,
            signed_pre_key: &mut self.signed_pre_key_store,
            kyber_pre_key: &mut self.kyber_pre_key_store,
        }
    }
}

/// Five independently owned stores (what the app bridges construct).
pub struct SeparateStores<S, I, P, SP, K> {
    pub session: S,
    pub identity: I,
    pub pre_key: P,
    pub signed_pre_key: SP,
    pub kyber_pre_key: K,
}

impl<S, I, P, SP, K> ProtocolStores for SeparateStores<S, I, P, SP, K>
where
    S: SessionStore + Send,
    I: IdentityKeyStore + Send,
    P: PreKeyStore + Send,
    SP: SignedPreKeyStore + Send,
    K: KyberPreKeyStore + Send,
{
    fn parts(&mut self) -> StoreParts<'_> {
        StoreParts {
            session: &mut self.session,
            identity: &mut self.identity,
            pre_key: &mut self.pre_key,
            signed_pre_key: &mut self.signed_pre_key,
            kyber_pre_key: &mut self.kyber_pre_key,
        }
    }
}
