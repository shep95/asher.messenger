// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// An RNode (or any KISS TNC) on a serial port, through Electron's Web Serial
// support. `navigator.serial.requestPort()` makes main emit
// `select-serial-port`; main forwards the candidates to Preferences > Mesh and
// answers Electron with the port the user picked (app/mesh_channel.main.ts),
// so only a user-chosen port is ever opened. KISS framing is done here
// (ts/mesh/kiss.std.ts); the node sees plain frames.

import { createLogger } from '../../logging/log.std.ts';
import { LINK_OPTIONS_LORA } from '../constants.std.ts';
import {
  encodeKiss,
  KissCommand,
  KissDecoder,
  radioConfigFrames,
  radioFrameMtu,
  type RadioConfigType,
} from '../kiss.std.ts';
import type { AttachedLink, LinkHost } from './Link.std.ts';

const log = createLogger('MeshSerialLink');

const RNODE_BAUD_RATE = 115200;

// Web Serial is not in TypeScript's DOM lib; these are the pieces we touch.
type SerialPortLike = {
  open: (options: { baudRate: number }) => Promise<void>;
  close: () => Promise<void>;
  readable: ReadableStream<Uint8Array> | null;
  writable: WritableStream<Uint8Array> | null;
  getInfo: () => { usbVendorId?: number; usbProductId?: number };
  addEventListener: (type: 'disconnect', listener: () => void) => void;
};
type SerialLike = {
  requestPort: () => Promise<SerialPortLike>;
};

function getSerial(): SerialLike | undefined {
  return (navigator as unknown as { serial?: SerialLike }).serial;
}

export function isWebSerialAvailable(): boolean {
  return getSerial() != null;
}

export type SerialLinkOptions = Readonly<{
  /** Send the RNode radio configuration frames after opening. */
  radioConfig?: RadioConfigType;
}>;

/**
 * Asks the user for a port, opens it, and attaches it as a LoRa-paced link.
 * Resolves with the link once frames can flow.
 */
export async function openSerialLink(
  host: LinkHost,
  { radioConfig }: SerialLinkOptions = {}
): Promise<AttachedLink> {
  const serial = getSerial();
  if (!serial) {
    throw new Error('Web Serial is not available in this window');
  }
  const port = await serial.requestPort();
  await port.open({ baudRate: RNODE_BAUD_RATE });
  if (!port.readable || !port.writable) {
    await port.close();
    throw new Error('serial port has no streams');
  }

  const writer = port.writable.getWriter();
  const reader = port.readable.getReader();
  const info = port.getInfo();
  const label = `radio ${info.usbVendorId?.toString(16) ?? '?'}:${
    info.usbProductId?.toString(16) ?? '?'
  }`;
  let closed = false;

  const mtu = radioConfig ? radioFrameMtu(radioConfig) : LINK_OPTIONS_LORA.mtu;
  const link = host.attachLink(
    {
      kind: 'serial',
      label,
      send: async frame => {
        try {
          await writer.write(encodeKiss(KissCommand.Data, frame));
        } catch (error) {
          log.warn(`write failed: ${error}`);
        }
      },
      close: async () => {
        if (closed) {
          return;
        }
        closed = true;
        try {
          await reader.cancel();
          reader.releaseLock();
          writer.releaseLock();
          await port.close();
        } catch (error) {
          log.warn(`close failed: ${error}`);
        }
      },
    },
    { ...LINK_OPTIONS_LORA, mtu }
  );

  if (radioConfig) {
    for (const frame of radioConfigFrames(radioConfig)) {
      // eslint-disable-next-line no-await-in-loop
      await writer.write(frame);
    }
  }

  port.addEventListener('disconnect', () => {
    log.info('port disconnected');
    link.detach();
  });

  const decoder = new KissDecoder();
  void (async () => {
    try {
      while (!closed) {
        // eslint-disable-next-line no-await-in-loop
        const { value, done } = await reader.read();
        if (done) {
          break;
        }
        if (!value) {
          continue;
        }
        for (const { command, payload } of decoder.feed(value)) {
          if (command === KissCommand.Data && payload.length > 0) {
            link.deliver(payload);
          }
        }
      }
    } catch (error) {
      if (!closed) {
        log.warn(`read loop ended: ${error}`);
      }
    }
    link.detach();
  })();

  return link;
}
