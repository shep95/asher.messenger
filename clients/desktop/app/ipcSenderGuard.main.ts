// Copyright 2024 Signal Messenger, LLC
// SPDX-License-Identifier: AGPL-3.0-only

import type { IpcMainEvent, IpcMainInvokeEvent, WebContents } from 'electron';

import { createLogger } from '../ts/logging/log.std.ts';

const log = createLogger('ipcSenderGuard');

// Every BrowserWindow in the app can reach every `ipcMain` handler. Most handlers are
// harmless, but a handful destroy or exfiltrate the profile (drop the database, delete the
// SQLCipher key, wipe attachments, resolve arbitrary hostnames). Those must only be
// callable from the main window, whose renderer is the only one that legitimately drives
// them. The main window registers its WebContents here as soon as it is created.

let privilegedWebContentsId: number | undefined;

export function setPrivilegedWebContents(webContents: WebContents): void {
  privilegedWebContentsId = webContents.id;
}

export function isPrivilegedSender(
  event: IpcMainEvent | IpcMainInvokeEvent
): boolean {
  return (
    privilegedWebContentsId != null &&
    event.sender.id === privilegedWebContentsId
  );
}

export function assertPrivilegedSender(
  event: IpcMainEvent | IpcMainInvokeEvent,
  channel: string
): void {
  if (isPrivilegedSender(event)) {
    return;
  }
  log.warn(
    `Rejected IPC '${channel}' from non-main webContents ${event.sender.id}`
  );
  throw new Error(`IPC channel '${channel}' is only available to the main window`);
}
