//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Tiny length-prefixed binary codec. Everything on a meshlink link is encoded
//! with these helpers so that the format has no dependency on a schema
//! compiler and stays small enough for 200-byte LoRa frames.

use crate::{Error, Result};

#[derive(Default)]
pub struct Writer(Vec<u8>);

impl Writer {
    pub fn new() -> Self {
        Self(Vec::new())
    }
    pub fn u8(&mut self, v: u8) -> &mut Self {
        self.0.push(v);
        self
    }
    pub fn u16(&mut self, v: u16) -> &mut Self {
        self.0.extend_from_slice(&v.to_be_bytes());
        self
    }
    pub fn u32(&mut self, v: u32) -> &mut Self {
        self.0.extend_from_slice(&v.to_be_bytes());
        self
    }
    pub fn u64(&mut self, v: u64) -> &mut Self {
        self.0.extend_from_slice(&v.to_be_bytes());
        self
    }
    pub fn fixed(&mut self, v: &[u8]) -> &mut Self {
        self.0.extend_from_slice(v);
        self
    }
    /// Length-prefixed (u16) byte string.
    pub fn bytes(&mut self, v: &[u8]) -> &mut Self {
        debug_assert!(v.len() <= u16::MAX as usize);
        self.u16(v.len() as u16);
        self.0.extend_from_slice(v);
        self
    }
    pub fn finish(self) -> Vec<u8> {
        self.0
    }
}

pub struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    pub fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        if self.pos + n > self.data.len() {
            return Err(Error::Wire("truncated"));
        }
        let s = &self.data[self.pos..self.pos + n];
        self.pos += n;
        Ok(s)
    }
    pub fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    pub fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_be_bytes(
            self.take(2)?.try_into().expect("2 bytes"),
        ))
    }
    pub fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_be_bytes(
            self.take(4)?.try_into().expect("4 bytes"),
        ))
    }
    pub fn u64(&mut self) -> Result<u64> {
        Ok(u64::from_be_bytes(
            self.take(8)?.try_into().expect("8 bytes"),
        ))
    }
    pub fn fixed<const N: usize>(&mut self) -> Result<[u8; N]> {
        Ok(self.take(N)?.try_into().expect("N bytes"))
    }
    pub fn bytes(&mut self) -> Result<&'a [u8]> {
        let n = self.u16()? as usize;
        self.take(n)
    }
    pub fn remaining(&self) -> usize {
        self.data.len() - self.pos
    }
    pub fn rest(&mut self) -> &'a [u8] {
        let s = &self.data[self.pos..];
        self.pos = self.data.len();
        s
    }
    pub fn finish(self) -> Result<()> {
        if self.remaining() == 0 {
            Ok(())
        } else {
            Err(Error::Wire("trailing bytes"))
        }
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn round_trip() {
        let mut w = Writer::new();
        w.u8(7)
            .u16(513)
            .u32(70000)
            .u64(1 << 40)
            .bytes(b"abc")
            .fixed(&[1, 2]);
        let buf = w.finish();
        let mut r = Reader::new(&buf);
        assert_eq!(r.u8().unwrap(), 7);
        assert_eq!(r.u16().unwrap(), 513);
        assert_eq!(r.u32().unwrap(), 70000);
        assert_eq!(r.u64().unwrap(), 1 << 40);
        assert_eq!(r.bytes().unwrap(), b"abc");
        assert_eq!(r.fixed::<2>().unwrap(), [1, 2]);
        r.finish().unwrap();
        assert!(Reader::new(&buf[..3]).u32().is_err());
    }
}
