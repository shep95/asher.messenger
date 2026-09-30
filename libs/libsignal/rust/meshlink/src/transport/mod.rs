//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Links are byte pipes. A transport (Bluetooth LE, a KISS radio, a TCP
//! socket, a test channel) obtains a [`LinkEndpoint`] from
//! [`crate::Node::attach_link`], feeds received frames into `inbound` and
//! writes frames it takes from `outbound` to the wire. Nothing else is
//! required of it, which keeps every platform-specific piece outside the
//! protocol core.

pub mod memory;

use tokio::sync::mpsc;

/// The node's side of a link.
pub struct LinkEndpoint {
    pub id: crate::LinkId,
    /// Frames received from the wire go here.
    pub inbound: mpsc::Sender<Vec<u8>>,
    /// Frames the node wants sent on the wire come out here.
    pub outbound: mpsc::Receiver<Vec<u8>>,
}
