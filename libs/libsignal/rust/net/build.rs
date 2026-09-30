//
// Copyright 2023 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//

fn main() {
    // Brand builds may pin a custom root CA for the hosts overridden through
    // LIBSIGNAL_BRAND_* (see src/env/brand.rs). Copy it into OUT_DIR so it can be
    // include_bytes!'d unconditionally; write an empty file when unset.
    let out_dir = std::env::var("OUT_DIR").expect("OUT_DIR is set by cargo");
    let brand_ca = std::path::Path::new(&out_dir).join("brand_root_ca.der");
    println!("cargo:rerun-if-env-changed=LIBSIGNAL_BRAND_ROOT_CA_DER");
    match std::env::var_os("LIBSIGNAL_BRAND_ROOT_CA_DER") {
        Some(path) if !path.is_empty() => {
            println!("cargo:rerun-if-changed={}", path.to_string_lossy());
            std::fs::copy(&path, &brand_ca).unwrap_or_else(|e| {
                panic!("cannot read LIBSIGNAL_BRAND_ROOT_CA_DER {path:?}: {e}")
            });
        }
        _ => std::fs::write(&brand_ca, []).expect("can write to OUT_DIR"),
    }

    let protos = [
        "src/proto/cds2.proto",
        "src/proto/chat_provisioning.proto",
        "src/proto/chat_websocket.proto",
        "src/proto/svr2.proto",
    ];
    prost_build::Config::new()
        .bytes([
            ".signal.proto.chat_provisioning",
            ".signal.proto.chat_websocket",
        ])
        .compile_protos(&protos, &["src"])
        .expect("Protobufs in src are valid");
    for proto in &protos {
        println!("cargo:rerun-if-changed={proto}");
    }
}
