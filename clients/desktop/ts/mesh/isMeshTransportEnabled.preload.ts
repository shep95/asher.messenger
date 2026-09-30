// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// The `mesh.transport` feature flag. Off by default. It is on when either the
// remote config enables it or the user flipped the local override in
// Preferences > Mesh (stored as the `meshTransportEnabled` item), the same
// pattern Desktop uses for other locally-overridable `desktop.*` flags.

import type { ReadonlyDeep } from 'type-fest';

import { isEnabled, type ConfigMapType } from '../RemoteConfig.dom.ts';
import { itemStorage } from '../textsecure/Storage.preload.ts';
import { MESH_TRANSPORT_FLAG } from './constants.std.ts';

export type MeshFlagItemsType = Readonly<{
  meshTransportEnabled?: boolean;
  remoteConfig?: ReadonlyDeep<ConfigMapType> | undefined;
}>;

/** For selectors and components: decide from redux `items`. */
export function isMeshTransportEnabledFromItems(
  items: MeshFlagItemsType
): boolean {
  if (items.meshTransportEnabled === true) {
    return true;
  }
  return items.remoteConfig?.[MESH_TRANSPORT_FLAG]?.enabled ?? false;
}

/** For services: decide from storage and the live remote config. */
export function isMeshTransportEnabled(): boolean {
  if (itemStorage.get('meshTransportEnabled') === true) {
    return true;
  }
  try {
    return isEnabled(MESH_TRANSPORT_FLAG);
  } catch {
    // Remote config not loaded yet; the flag is off until it is.
    return false;
  }
}
