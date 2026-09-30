//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! `meshlinkd`: a relay / gateway node for a Raspberry Pi, a laptop or a
//! server at the edge of the mesh. It carries bundles between whatever links
//! it is given (TCP peers, a KISS radio such as an RNode on a serial device)
//! and never holds anyone's keys but its own.
//!
//! ```text
//! meshlinkd --state /var/lib/meshlink --name "hut gateway" \
//!           --listen 0.0.0.0:7788 --connect 10.0.0.2:7788 \
//!           --serial /dev/ttyUSB0 --lora-eu --beacon 600
//! ```
//!
//! The serial device is opened as a plain file; set its speed first
//! (`stty -F /dev/ttyUSB0 115200 raw -echo`). Frames on it are KISS.

use std::io::{Read as _, Write as _};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::time::Duration;

use meshlink::kiss;
use meshlink::transport::{LinkOptions, tcp};
use meshlink::{FilePersistence, MeshIdentity, Node, NodeConfig};
use rand::TryRngCore as _;

struct Args {
    state: PathBuf,
    name: String,
    listen: Vec<SocketAddr>,
    connect: Vec<SocketAddr>,
    serial: Option<PathBuf>,
    serial_mtu: usize,
    lora_eu: bool,
    beacon_secs: u64,
    max_peers: usize,
    print_card: bool,
}

fn usage() -> ! {
    eprintln!(
        "usage: meshlinkd --state DIR [--name NAME] [--listen ADDR]... [--connect ADDR]...\n\
         \x20               [--serial DEVICE [--serial-mtu N] [--lora-eu]] [--beacon SECS]\n\
         \x20               [--max-peers N] [--print-card]"
    );
    std::process::exit(2)
}

fn parse() -> Args {
    let mut a = Args {
        state: PathBuf::new(),
        name: String::from("gateway"),
        listen: Vec::new(),
        connect: Vec::new(),
        serial: None,
        serial_mtu: 200,
        lora_eu: false,
        beacon_secs: 0,
        max_peers: 64,
        print_card: false,
    };
    let mut it = std::env::args().skip(1);
    while let Some(flag) = it.next() {
        let mut value = || it.next().unwrap_or_else(|| usage());
        match flag.as_str() {
            "--state" => a.state = PathBuf::from(value()),
            "--name" => a.name = value(),
            "--listen" => a.listen.push(value().parse().unwrap_or_else(|_| usage())),
            "--connect" => a.connect.push(value().parse().unwrap_or_else(|_| usage())),
            "--serial" => a.serial = Some(PathBuf::from(value())),
            "--serial-mtu" => a.serial_mtu = value().parse().unwrap_or_else(|_| usage()),
            "--lora-eu" => a.lora_eu = true,
            "--beacon" => a.beacon_secs = value().parse().unwrap_or_else(|_| usage()),
            "--max-peers" => a.max_peers = value().parse().unwrap_or_else(|_| usage()),
            "--print-card" => a.print_card = true,
            _ => usage(),
        }
    }
    if a.state.as_os_str().is_empty() {
        usage();
    }
    a
}

fn load_or_create_identity(dir: &std::path::Path, name: &str) -> meshlink::Result<MeshIdentity> {
    let path = dir.join("identity.bin");
    let mut rng = rand::rngs::OsRng.unwrap_err();
    match std::fs::read(&path) {
        Ok(bytes) => MeshIdentity::import(&bytes, &mut rng),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            std::fs::create_dir_all(dir)?;
            let id = MeshIdentity::generate(name, &mut rng)?;
            let tmp = path.with_extension("tmp");
            std::fs::write(&tmp, id.export()?)?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt as _;
                std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600))?;
            }
            std::fs::rename(&tmp, &path)?;
            Ok(id)
        }
        Err(e) => Err(e.into()),
    }
}

/// A KISS radio on a character device: one blocking reader thread, one
/// blocking writer thread, the node in between.
fn run_serial(node: Node, device: PathBuf, mtu: usize, lora_eu: bool) -> meshlink::Result<()> {
    let mut reader = std::fs::OpenOptions::new().read(true).open(&device)?;
    let mut writer = std::fs::OpenOptions::new().write(true).open(&device)?;
    let endpoint = node.attach_link_with(LinkOptions::lora(mtu));
    let inbound = endpoint.inbound;
    let mut outbound = endpoint.outbound;
    if lora_eu {
        for frame in kiss::RadioConfig::EU_LONG_RANGE.to_frames() {
            writer.write_all(&frame)?;
        }
        writer.flush()?;
    }
    std::thread::Builder::new()
        .name("serial-rx".into())
        .spawn(move || {
            let mut decoder = kiss::Decoder::new();
            let mut buf = [0u8; 512];
            loop {
                let n = match reader.read(&mut buf) {
                    Ok(0) => {
                        std::thread::sleep(Duration::from_millis(20));
                        continue;
                    }
                    Ok(n) => n,
                    Err(e) => {
                        log::error!("serial read: {e}");
                        return;
                    }
                };
                for (cmd, payload) in decoder.feed(&buf[..n]) {
                    if cmd == kiss::cmd::DATA && inbound.blocking_send(payload).is_err() {
                        return;
                    }
                }
            }
        })?;
    std::thread::Builder::new()
        .name("serial-tx".into())
        .spawn(move || {
            while let Some(frame) = outbound.blocking_recv() {
                let bytes = kiss::encode(kiss::cmd::DATA, &frame);
                if let Err(e) = writer.write_all(&bytes).and_then(|_| writer.flush()) {
                    log::error!("serial write: {e}");
                    return;
                }
            }
        })?;
    Ok(())
}

#[tokio::main]
async fn main() -> meshlink::Result<()> {
    let args = parse();
    let identity = load_or_create_identity(&args.state, &args.name)?;
    if args.print_card {
        println!("{}", identity.card().to_base64());
    }
    eprintln!(
        "meshlinkd {} ({})",
        meshlink::bundle::fingerprint_hex(&identity.fingerprint()),
        identity.card().name
    );
    let node = Node::builder(identity)
        .config(NodeConfig::default())
        .persistence(Box::new(FilePersistence::new(args.state.join("mesh.bin"))))
        .start()?;

    for addr in &args.listen {
        let listener = tokio::net::TcpListener::bind(addr).await?;
        eprintln!("listening on {addr}");
        tokio::spawn(tcp::serve(
            node.clone(),
            listener,
            LinkOptions::new(1500),
            args.max_peers,
        ));
    }
    for addr in args.connect.clone() {
        let node = node.clone();
        tokio::spawn(async move {
            loop {
                match tcp::connect(&node, addr, LinkOptions::new(1500)).await {
                    Ok(()) => log::info!("peer {addr} closed; reconnecting"),
                    Err(e) => log::warn!("peer {addr}: {e}; retrying"),
                }
                tokio::time::sleep(Duration::from_secs(10)).await;
            }
        });
    }
    if let Some(device) = args.serial.clone() {
        run_serial(node.clone(), device, args.serial_mtu, args.lora_eu)?;
        eprintln!("radio on {}", args.serial.as_ref().expect("set").display());
    }
    if args.beacon_secs > 0 {
        let node = node.clone();
        let every = Duration::from_secs(args.beacon_secs);
        tokio::spawn(async move {
            loop {
                if let Err(e) = node.broadcast_card().await {
                    log::warn!("beacon: {e}");
                }
                tokio::time::sleep(every).await;
            }
        });
    }

    let mut events = node.subscribe();
    let mut stats_tick = tokio::time::interval(Duration::from_secs(60));
    loop {
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {
                eprintln!("flushing state");
                node.flush().await?;
                return Ok(());
            }
            ev = events.recv() => {
                if let Ok(ev) = ev {
                    match ev {
                        meshlink::Event::Neighbour { link, fingerprint } => {
                            eprintln!("link {link}: neighbour {}", hex::encode(fingerprint));
                        }
                        meshlink::Event::LinkClosed { link } => eprintln!("link {link} closed"),
                        _ => {}
                    }
                }
            }
            _ = stats_tick.tick() => {
                let s = node.stats().await;
                eprintln!(
                    "links {} store {} bundles / {} bytes; in {} fwd {} dropped {}",
                    s.links, s.store_bundles, s.store_bytes, s.bundles_in, s.bundles_forwarded,
                    s.bundles_dropped_rate + s.bundles_dropped_quota + s.bundles_dropped_invalid
                );
            }
        }
    }
}
