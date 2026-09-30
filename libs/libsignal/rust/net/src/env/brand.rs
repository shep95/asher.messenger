//
// Copyright 2023 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Compile-time "brand" overrides for the [`PROD`](super::PROD) environment.
//!
//! libsignal only exposes two environments to its Java, Swift and Node bindings:
//! `Staging` and `Production`. Every host, root certificate, enclave measurement
//! and key-transparency key behind those two names is compiled into this crate.
//! A white-label deployment that runs its own `Signal-Server`, CDSI and SVR
//! therefore needs its own libsignal build.
//!
//! Rather than adding a third environment (which would ripple through every
//! binding), this module lets the *build* retarget `Production`. Set any of the
//! `LIBSIGNAL_BRAND_*` environment variables below when invoking `cargo` (or the
//! platform build scripts under `java/`, `node/` and `swift/`) and the resulting
//! library's `Production` environment points at the brand's infrastructure.
//! Clients keep selecting `Environment.PRODUCTION` and need no code change.
//!
//! When none of the variables are set this module is inert and `PROD` is exactly
//! Signal's production environment.
//!
//! | Variable | Effect |
//! |---|---|
//! | `LIBSIGNAL_BRAND_CHAT_HOST` | Hostname of the chat service (the `Signal-Server` H2/websocket front, port 443). |
//! | `LIBSIGNAL_BRAND_CDSI_HOST` | Hostname of the contact-discovery service. |
//! | `LIBSIGNAL_BRAND_SVR2_HOST` | Hostname of SVR2. |
//! | `LIBSIGNAL_BRAND_SVRB_HOST` | Hostname of SVR-B (backup key SVR). |
//! | `LIBSIGNAL_BRAND_ROOT_CA_DER` | Path to a DER-encoded root certificate to pin for the hosts above. If unset, the platform trust store is used. |
//! | `LIBSIGNAL_BRAND_CDSI_MRENCLAVE` | 64 hex chars: MRENCLAVE of the brand's CDSI enclave. |
//! | `LIBSIGNAL_BRAND_SVR2_MRENCLAVE` | 64 hex chars: MRENCLAVE of the brand's SVR2 enclave. |
//! | `LIBSIGNAL_BRAND_SVRB_MRENCLAVE` | 64 hex chars: MRENCLAVE of the brand's SVR-B enclave. |
//! | `LIBSIGNAL_BRAND_SVR2_RAFT_GROUP_ID` | Decimal raft group id of the brand's SVR2 cluster. |
//! | `LIBSIGNAL_BRAND_SVRB_RAFT_GROUP_ID` | Decimal raft group id of the brand's SVR-B cluster. |
//! | `LIBSIGNAL_BRAND_KEYTRANS_SIGNING_KEY` | 64 hex chars: key-transparency log signing key. |
//! | `LIBSIGNAL_BRAND_KEYTRANS_VRF_KEY` | 64 hex chars: key-transparency VRF public key. |
//!
//! Overriding a host also disables Signal's static-IP DNS fallback and the
//! censorship-circumvention reflectors for that host, since neither applies to
//! a third-party deployment.

use std::net::{Ipv4Addr, Ipv6Addr};

use attest::svr2::RaftConfig;
use libsignal_net_infra::certs::RootCertificates;

use crate::certs::SIGNAL_ROOT_CERTIFICATES;

pub const CHAT_HOST: Option<&str> = option_env!("LIBSIGNAL_BRAND_CHAT_HOST");
pub const CDSI_HOST: Option<&str> = option_env!("LIBSIGNAL_BRAND_CDSI_HOST");
pub const SVR2_HOST: Option<&str> = option_env!("LIBSIGNAL_BRAND_SVR2_HOST");
pub const SVRB_HOST: Option<&str> = option_env!("LIBSIGNAL_BRAND_SVRB_HOST");

const CDSI_MRENCLAVE_HEX: Option<&str> = option_env!("LIBSIGNAL_BRAND_CDSI_MRENCLAVE");
const SVR2_MRENCLAVE_HEX: Option<&str> = option_env!("LIBSIGNAL_BRAND_SVR2_MRENCLAVE");
const SVRB_MRENCLAVE_HEX: Option<&str> = option_env!("LIBSIGNAL_BRAND_SVRB_MRENCLAVE");
const SVR2_RAFT_GROUP_ID_DEC: Option<&str> = option_env!("LIBSIGNAL_BRAND_SVR2_RAFT_GROUP_ID");
const SVRB_RAFT_GROUP_ID_DEC: Option<&str> = option_env!("LIBSIGNAL_BRAND_SVRB_RAFT_GROUP_ID");
const KEYTRANS_SIGNING_KEY_HEX: Option<&str> = option_env!("LIBSIGNAL_BRAND_KEYTRANS_SIGNING_KEY");
const KEYTRANS_VRF_KEY_HEX: Option<&str> = option_env!("LIBSIGNAL_BRAND_KEYTRANS_VRF_KEY");

/// `true` if any host is overridden, i.e. this is a brand build.
pub const ENABLED: bool =
    CHAT_HOST.is_some() || CDSI_HOST.is_some() || SVR2_HOST.is_some() || SVRB_HOST.is_some();

/// Contents of `LIBSIGNAL_BRAND_ROOT_CA_DER`, copied into `OUT_DIR` by `build.rs`.
/// Empty when the variable is unset.
const ROOT_CA_DER: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/brand_root_ca.der"));

/// Root certificates for brand-overridden hosts: the pinned brand CA if one was
/// supplied at build time, otherwise the platform trust store.
pub const ROOT_CERTIFICATES: RootCertificates = if ROOT_CA_DER.is_empty() {
    RootCertificates::Native
} else {
    RootCertificates::FromStaticDers(&[ROOT_CA_DER])
};

/// Picks the brand host if set, otherwise Signal's.
pub const fn host(default: &'static str, brand: Option<&'static str>) -> &'static str {
    match brand {
        Some(h) => h,
        None => default,
    }
}

/// Root certificates to use for a host, depending on whether it is overridden.
pub const fn certs(brand: Option<&'static str>) -> RootCertificates {
    if brand.is_some() {
        ROOT_CERTIFICATES
    } else {
        SIGNAL_ROOT_CERTIFICATES
    }
}

/// Static IPv4 fallback: none for an overridden host.
pub const fn ip_v4(
    brand: Option<&'static str>,
    default: &'static [Ipv4Addr],
) -> &'static [Ipv4Addr] {
    if brand.is_some() { &[] } else { default }
}

/// Static IPv6 fallback: none for an overridden host.
pub const fn ip_v6(
    brand: Option<&'static str>,
    default: &'static [Ipv6Addr],
) -> &'static [Ipv6Addr] {
    if brand.is_some() { &[] } else { default }
}

const fn hex_nibble(c: u8) -> u8 {
    match c {
        b'0'..=b'9' => c - b'0',
        b'a'..=b'f' => c - b'a' + 10,
        b'A'..=b'F' => c - b'A' + 10,
        _ => panic!("LIBSIGNAL_BRAND_* value is not valid hex"),
    }
}

const fn decode_hex_32(s: &str) -> [u8; 32] {
    let bytes = s.as_bytes();
    assert!(
        bytes.len() == 64,
        "LIBSIGNAL_BRAND_* hex value must be exactly 64 characters (32 bytes)"
    );
    let mut out = [0u8; 32];
    let mut i = 0;
    while i < 32 {
        out[i] = (hex_nibble(bytes[2 * i]) << 4) | hex_nibble(bytes[2 * i + 1]);
        i += 1;
    }
    out
}

const fn parse_u64(s: &str) -> u64 {
    let bytes = s.as_bytes();
    assert!(
        !bytes.is_empty(),
        "LIBSIGNAL_BRAND_*_RAFT_GROUP_ID must not be empty"
    );
    let mut value: u64 = 0;
    let mut i = 0;
    while i < bytes.len() {
        let c = bytes[i];
        assert!(
            c.is_ascii_digit(),
            "LIBSIGNAL_BRAND_*_RAFT_GROUP_ID must be a decimal integer"
        );
        value = value * 10 + (c - b'0') as u64;
        i += 1;
    }
    value
}

const fn hex_32_or(over: Option<&str>, default: [u8; 32]) -> [u8; 32] {
    match over {
        Some(h) => decode_hex_32(h),
        None => default,
    }
}

const CDSI_MRENCLAVE_BYTES: [u8; 32] = hex_32_or(CDSI_MRENCLAVE_HEX, [0; 32]);
const SVR2_MRENCLAVE_BYTES: [u8; 32] = hex_32_or(SVR2_MRENCLAVE_HEX, [0; 32]);
const SVRB_MRENCLAVE_BYTES: [u8; 32] = hex_32_or(SVRB_MRENCLAVE_HEX, [0; 32]);

/// MRENCLAVE for the production CDSI endpoint.
pub const CDSI_MRENCLAVE: &[u8] = match CDSI_MRENCLAVE_HEX {
    Some(_) => &CDSI_MRENCLAVE_BYTES,
    None => attest::constants::ENCLAVE_ID_CDSI_PROD,
};

/// MRENCLAVE for the current production SVR2 endpoint.
pub const SVR2_MRENCLAVE: &[u8] = match SVR2_MRENCLAVE_HEX {
    Some(_) => &SVR2_MRENCLAVE_BYTES,
    None => attest::constants::ENCLAVE_ID_SVR2_2026Q3_PROD,
};

/// MRENCLAVE for the current production SVR-B endpoint.
pub const SVRB_MRENCLAVE: &[u8] = match SVRB_MRENCLAVE_HEX {
    Some(_) => &SVRB_MRENCLAVE_BYTES,
    None => attest::constants::ENCLAVE_ID_SVRB_2026Q3_PROD,
};

/// Raft config for the current production SVR2 endpoint, with the brand's group id if set.
pub const SVR2_RAFT_CONFIG: &RaftConfig = match SVR2_RAFT_GROUP_ID_DEC {
    Some(id) => &RaftConfig {
        group_id: parse_u64(id),
        ..*attest::constants::RAFT_CONFIG_SVR2_2026Q3_PROD
    },
    None => attest::constants::RAFT_CONFIG_SVR2_2026Q3_PROD,
};

/// Raft config for the current production SVR-B endpoint, with the brand's group id if set.
pub const SVRB_RAFT_CONFIG: &RaftConfig = match SVRB_RAFT_GROUP_ID_DEC {
    Some(id) => &RaftConfig {
        group_id: parse_u64(id),
        ..*attest::constants::RAFT_CONFIG_SVRB_2026Q3_PROD
    },
    None => attest::constants::RAFT_CONFIG_SVRB_2026Q3_PROD,
};

/// `true` if the key-transparency keys are overridden.
pub const KEYTRANS_ENABLED: bool =
    KEYTRANS_SIGNING_KEY_HEX.is_some() || KEYTRANS_VRF_KEY_HEX.is_some();

const KEYTRANS_SIGNING_KEY_BYTES: [u8; 32] = hex_32_or(KEYTRANS_SIGNING_KEY_HEX, [0; 32]);
const KEYTRANS_VRF_KEY_BYTES: [u8; 32] = hex_32_or(KEYTRANS_VRF_KEY_HEX, [0; 32]);

/// Key-transparency log signing key for production.
pub const KEYTRANS_SIGNING_KEY: &[u8; 32] = match KEYTRANS_SIGNING_KEY_HEX {
    Some(_) => &KEYTRANS_SIGNING_KEY_BYTES,
    None => super::KEYTRANS_SIGNING_KEY_MATERIAL_PROD,
};

/// Key-transparency VRF key for production.
pub const KEYTRANS_VRF_KEY: &[u8; 32] = match KEYTRANS_VRF_KEY_HEX {
    Some(_) => &KEYTRANS_VRF_KEY_BYTES,
    None => super::KEYTRANS_VRF_KEY_MATERIAL_PROD,
};

/// Key-transparency auditor keys for production. A brand log has no Signal auditors.
pub const KEYTRANS_AUDITOR_KEYS: &[&[u8; 32]] = if KEYTRANS_ENABLED {
    &[]
} else {
    super::KEYTRANS_AUDITOR_KEY_MATERIAL_PROD
};

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn hex_decoding_round_trips() {
        let hex = "15637fa1e54fe655176d3df1a9f94b87c01ed377acaa570682dc5d72c95ef07b";
        assert_eq!(
            decode_hex_32(hex).as_slice(),
            attest::constants::ENCLAVE_ID_CDSI_PROD
        );
        assert_eq!(parse_u64("18446744073709551615"), u64::MAX);
        assert_eq!(parse_u64("0"), 0);
    }

    #[test]
    fn defaults_match_signal_when_not_branded() {
        if ENABLED {
            return;
        }
        assert_eq!(CDSI_MRENCLAVE, attest::constants::ENCLAVE_ID_CDSI_PROD);
        assert_eq!(
            SVR2_MRENCLAVE,
            attest::constants::ENCLAVE_ID_SVR2_2026Q3_PROD
        );
        assert_eq!(
            host("grpc.chat.signal.org", CHAT_HOST),
            "grpc.chat.signal.org"
        );
        assert!(matches!(
            certs(CHAT_HOST),
            RootCertificates::FromStaticDers(_)
        ));
    }
}
