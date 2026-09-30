//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Frames over a TCP stream: `u16 big-endian length` then the frame. Used for
//! LAN links between nodes, for gateway daemons, and for tunnels through
//! anything that presents a socket (a satellite terminal's IP link, an SSH
//! forward). No security is added at this layer: bundles are already
//! end-to-end encrypted and a peer here is just another relay.

use std::net::SocketAddr;

use tokio::io::{AsyncReadExt as _, AsyncWriteExt as _};
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinHandle;

use crate::node::MAX_FRAME_LEN;
use crate::transport::LinkOptions;
use crate::{Node, Result};

/// Attaches an established stream as a link. Returns when the stream ends.
pub async fn run_stream(node: &Node, stream: TcpStream, options: LinkOptions) -> Result<()> {
    stream.set_nodelay(true)?;
    let endpoint = node.attach_link_with(options);
    let id = endpoint.id;
    let (mut rd, mut wr) = stream.into_split();
    let inbound = endpoint.inbound;
    let mut outbound = endpoint.outbound;
    let reader: JoinHandle<Result<()>> = tokio::spawn(async move {
        let mut len = [0u8; 2];
        loop {
            if rd.read_exact(&mut len).await.is_err() {
                return Ok(());
            }
            let n = u16::from_be_bytes(len) as usize;
            if n == 0 || n > MAX_FRAME_LEN {
                return Err(crate::Error::Wire("tcp frame length out of range"));
            }
            let mut buf = vec![0u8; n];
            rd.read_exact(&mut buf).await?;
            if inbound.send(buf).await.is_err() {
                return Ok(());
            }
        }
    });
    let writer: JoinHandle<Result<()>> = tokio::spawn(async move {
        while let Some(frame) = outbound.recv().await {
            let n = u16::try_from(frame.len())
                .map_err(|_| crate::Error::Wire("frame too large for tcp"))?;
            wr.write_all(&n.to_be_bytes()).await?;
            wr.write_all(&frame).await?;
            wr.flush().await?;
        }
        Ok(())
    });
    let result = tokio::select! {
        r = reader => r.unwrap_or(Ok(())),
        r = writer => r.unwrap_or(Ok(())),
    };
    node.detach_link(id);
    result
}

/// Dials `addr` and runs the link until it closes.
pub async fn connect(node: &Node, addr: SocketAddr, options: LinkOptions) -> Result<()> {
    let stream = TcpStream::connect(addr).await?;
    run_stream(node, stream, options).await
}

/// Accepts connections forever, one link each. `max_peers` bounds how many
/// concurrent links a listener will hold so a port scan cannot exhaust the
/// node.
pub async fn serve(
    node: Node,
    listener: TcpListener,
    options: LinkOptions,
    max_peers: usize,
) -> Result<()> {
    let active = std::sync::Arc::new(tokio::sync::Semaphore::new(max_peers.max(1)));
    loop {
        let (stream, peer) = listener.accept().await?;
        let Ok(permit) = active.clone().try_acquire_owned() else {
            log::warn!("tcp: refusing {peer}: peer limit reached");
            continue;
        };
        let node = node.clone();
        let options = options.clone();
        tokio::spawn(async move {
            let _permit = permit;
            if let Err(e) = run_stream(&node, stream, options).await {
                log::debug!("tcp link with {peer} ended: {e}");
            }
        });
    }
}
