//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! An in-process link between two nodes, used by the tests and by the
//! simulation of intermittent contact (a phone carried from one place to
//! another).

use tokio::task::JoinHandle;

use crate::Node;

/// A live in-memory link. Dropping it, or calling [`MemoryLink::disconnect`],
/// severs the link at both ends.
pub struct MemoryLink {
    a: (Node, crate::LinkId),
    b: (Node, crate::LinkId),
    tasks: Vec<JoinHandle<()>>,
}

impl MemoryLink {
    /// Connects `a` and `b` with a bidirectional pipe whose frames are limited
    /// to `mtu` bytes.
    pub fn connect(a: &Node, b: &Node, mtu: usize) -> Self {
        Self::connect_with(a, b, crate::transport::LinkOptions::new(mtu))
    }

    /// Connects `a` and `b` with explicit link options (pacing, frame rate).
    pub fn connect_with(a: &Node, b: &Node, options: crate::transport::LinkOptions) -> Self {
        let ea = a.attach_link_with(options.clone());
        let eb = b.attach_link_with(options);
        let (a_id, b_id) = (ea.id, eb.id);
        let (mut a_out, b_in) = (ea.outbound, eb.inbound);
        let (mut b_out, a_in) = (eb.outbound, ea.inbound);
        let t1 = tokio::spawn(async move {
            while let Some(frame) = a_out.recv().await {
                if b_in.send(frame).await.is_err() {
                    break;
                }
            }
        });
        let t2 = tokio::spawn(async move {
            while let Some(frame) = b_out.recv().await {
                if a_in.send(frame).await.is_err() {
                    break;
                }
            }
        });
        Self {
            a: (a.clone(), a_id),
            b: (b.clone(), b_id),
            tasks: vec![t1, t2],
        }
    }

    pub fn disconnect(self) {
        drop(self);
    }
}

impl Drop for MemoryLink {
    fn drop(&mut self) {
        for t in &self.tasks {
            t.abort();
        }
        self.a.0.detach_link(self.a.1);
        self.b.0.detach_link(self.b.1);
    }
}
