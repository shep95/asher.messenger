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
    println!("cargo:rerun-if-env-changed=LIBSIGNAL_BRAND_ALLOW_PLATFORM_ROOTS");
    let host_vars = [
        "LIBSIGNAL_BRAND_CHAT_HOST",
        "LIBSIGNAL_BRAND_CDSI_HOST",
        "LIBSIGNAL_BRAND_SVR2_HOST",
        "LIBSIGNAL_BRAND_SVRB_HOST",
    ];
    for host_var in host_vars {
        println!("cargo:rerun-if-env-changed={host_var}");
    }
    let any_brand_host = host_vars
        .iter()
        .any(|var| std::env::var_os(var).is_some_and(|v| !v.is_empty()));
    let has_brand_ca =
        std::env::var_os("LIBSIGNAL_BRAND_ROOT_CA_DER").is_some_and(|v| !v.is_empty());
    let allow_platform_roots = std::env::var_os("LIBSIGNAL_BRAND_ALLOW_PLATFORM_ROOTS")
        .is_some_and(|v| v == "1" || v == "true");
    assert!(
        !any_brand_host || has_brand_ca || allow_platform_roots,
        "A LIBSIGNAL_BRAND_*_HOST is set without LIBSIGNAL_BRAND_ROOT_CA_DER. Upstream pins Signal's \
         roots for these connections; a brand build must pin its own CA. Set \
         LIBSIGNAL_BRAND_ALLOW_PLATFORM_ROOTS=1 to knowingly trust the platform certificate store instead."
    );
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
