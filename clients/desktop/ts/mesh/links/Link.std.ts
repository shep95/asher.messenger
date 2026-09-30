// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// A link is a byte pipe: frames the node wants on the wire go to
// `transport.send`, frames read from the wire come back through
// `AttachedLink.deliver`. How the bytes travel (TCP via the main process,
// KISS over Web Serial, GATT writes over Web Bluetooth) is the transport's
// business; the node only sees frames.

import type { LinkOptionsType } from '../constants.std.ts';
import type { MeshLinkKind } from '../../state/ducks/mesh.std.ts';

export type MeshLinkTransport = Readonly<{
  kind: MeshLinkKind;
  label: string;
  /** Put one frame on the wire. Errors are logged; the link keeps going. */
  send: (frame: Uint8Array) => void | Promise<void>;
  /** Tear the transport down; called once when the link is detached. */
  close: () => void | Promise<void>;
}>;

export type AttachedLink = Readonly<{
  key: string;
  options: LinkOptionsType;
  /** A frame arrived from the wire. */
  deliver: (frame: Uint8Array) => void;
  /** Detach from the node and close the transport. Idempotent. */
  detach: () => void;
}>;

/** What a transport needs from MeshService to become a link. */
export type LinkHost = Readonly<{
  attachLink: (
    transport: MeshLinkTransport,
    options: LinkOptionsType
  ) => AttachedLink;
}>;
