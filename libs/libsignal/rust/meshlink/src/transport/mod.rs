//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Links are byte pipes. meshlink frames go in one end and come out the
//! other; how the bytes travel (BLE characteristic writes, a KISS-framed
//! serial port, a TCP socket, a satellite modem's message queue) is the
//! transport's business and lives in the platform code.

pub mod memory;
pub mod tcp;

use tokio::sync::mpsc;

use crate::node::LinkId;

/// How a link behaves. Transports fill this in from what they know about the
/// medium so the node paces itself accordingly.
#[derive(Clone, Debug)]
pub struct LinkOptions {
    /// Largest frame the link carries in one piece.
    pub mtu: usize,
    /// Outbound pacing budget for this link in bytes per second. A LoRa
    /// radio at SF10/125 kHz sustains roughly 100-200 B/s; BLE a few tens of
    /// KiB/s; a LAN socket far more.
    pub max_bytes_per_sec: usize,
    /// Frames per second accepted from this link before further frames are
    /// dropped; protects the node from a chattering or hostile neighbour.
    pub max_frames_per_sec: usize,
}

impl LinkOptions {
    pub fn new(mtu: usize) -> Self {
        Self {
            mtu,
            max_bytes_per_sec: 256 * 1024,
            max_frames_per_sec: 500,
        }
    }

    /// Sensible defaults for an RNode-class LoRa link.
    pub fn lora(mtu: usize) -> Self {
        Self {
            mtu,
            max_bytes_per_sec: 200,
            max_frames_per_sec: 20,
        }
    }

    /// Sensible defaults for a Bluetooth LE link.
    pub fn ble(mtu: usize) -> Self {
        Self {
            mtu,
            max_bytes_per_sec: 16 * 1024,
            max_frames_per_sec: 200,
        }
    }
}

/// The node's side of a link.
///
/// * `inbound`: the transport sends every frame it receives from the wire here.
/// * `outbound`: the transport reads frames to put on the wire from here.
///
/// Dropping the endpoint (either half) detaches the link.
pub struct LinkEndpoint {
    pub id: LinkId,
    pub inbound: mpsc::Sender<Vec<u8>>,
    pub outbound: mpsc::Receiver<Vec<u8>>,
}
