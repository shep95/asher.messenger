//
// Copyright 2023 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Brand overrides for the SVR2 enclave lookup tables.
//!
//! `libsignal-net` retargets its `PROD` environment through `LIBSIGNAL_BRAND_*`
//! build-time variables (see `rust/net/src/env/brand.rs`). The SVR2 bridge,
//! however, derives PIN salts by looking the raft group id up *by enclave
//! measurement* in this crate's tables, which only know Signal's enclaves. This
//! module makes the same two variables visible here so that lookup succeeds
//! for a brand enclave instead of panicking in the bridge.

use crate::svr2::RaftConfig;

const SVR2_MRENCLAVE_HEX: Option<&str> = option_env!("LIBSIGNAL_BRAND_SVR2_MRENCLAVE");
const SVR2_RAFT_GROUP_ID_DEC: Option<&str> = option_env!("LIBSIGNAL_BRAND_SVR2_RAFT_GROUP_ID");

const _: () = assert!(
    SVR2_MRENCLAVE_HEX.is_none() || SVR2_RAFT_GROUP_ID_DEC.is_some(),
    "LIBSIGNAL_BRAND_SVR2_MRENCLAVE requires LIBSIGNAL_BRAND_SVR2_RAFT_GROUP_ID"
);

const fn hex_nibble(c: u8) -> u8 {
    match c {
        b'0'..=b'9' => c - b'0',
        b'a'..=b'f' => c - b'a' + 10,
        b'A'..=b'F' => c - b'A' + 10,
        _ => panic!("LIBSIGNAL_BRAND_SVR2_MRENCLAVE is not valid hex"),
    }
}

const fn decode_hex_32(s: &str) -> [u8; 32] {
    let bytes = s.as_bytes();
    assert!(
        bytes.len() == 64,
        "LIBSIGNAL_BRAND_SVR2_MRENCLAVE must be exactly 64 hex characters"
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
        "LIBSIGNAL_BRAND_SVR2_RAFT_GROUP_ID must not be empty"
    );
    let mut value: u64 = 0;
    let mut i = 0;
    while i < bytes.len() {
        assert!(
            bytes[i].is_ascii_digit(),
            "LIBSIGNAL_BRAND_SVR2_RAFT_GROUP_ID must be a decimal integer"
        );
        value = value * 10 + (bytes[i] - b'0') as u64;
        i += 1;
    }
    value
}

const SVR2_MRENCLAVE_BYTES: [u8; 32] = match SVR2_MRENCLAVE_HEX {
    Some(hex) => decode_hex_32(hex),
    None => [0; 32],
};

/// The brand's SVR2 raft config: Signal's current parameters with the brand's group id.
const SVR2_RAFT_CONFIG: RaftConfig = RaftConfig {
    group_id: match SVR2_RAFT_GROUP_ID_DEC {
        Some(id) => parse_u64(id),
        None => 0,
    },
    ..*crate::constants::RAFT_CONFIG_SVR2_2026Q3_PROD
};

/// Returns the brand's `(mrenclave, raft config)` if a brand SVR2 enclave is compiled in.
pub(crate) fn svr2() -> Option<(&'static [u8], &'static RaftConfig)> {
    match SVR2_MRENCLAVE_HEX {
        Some(_) => Some((&SVR2_MRENCLAVE_BYTES, &SVR2_RAFT_CONFIG)),
        None => None,
    }
}
