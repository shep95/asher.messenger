// Copyright 2026 Asher
// SPDX-License-Identifier: AGPL-3.0-only

import type { JSX } from 'react';
import { memo } from 'react';
import classNames from 'classnames';

import { missingCaseError } from '../../util/missingCaseError.std.ts';

// The scene indicator sits under the conversation title and says how the two
// of you are connected right now. It is the one place the transport shows
// itself (docs/design-system.md, rule 7). Labels and colours come from
// brands/asher/design/tokens.json → scene_indicator.
export type SceneState =
  | { kind: 'orbit' }
  | { kind: 'mesh'; hops: number }
  | { kind: 'carrying' }
  | { kind: 'offline' };

export type PropsType = Readonly<{
  scene: SceneState;
  className?: string;
}>;

export function getSceneLabel(scene: SceneState): string {
  switch (scene.kind) {
    case 'orbit':
      return 'Orbit';
    case 'mesh':
      return `Mesh · ${scene.hops} ${scene.hops === 1 ? 'hop' : 'hops'}`;
    case 'carrying':
      return 'Carrying';
    case 'offline':
      return 'Out of range';
    default:
      throw missingCaseError(scene);
  }
}

export const SceneIndicator = memo(function SceneIndicator({
  scene,
  className,
}: PropsType): JSX.Element {
  const label = getSceneLabel(scene);
  const isActive = scene.kind !== 'offline';

  return (
    <div
      className={classNames(
        'SceneIndicator',
        `SceneIndicator--${scene.kind}`,
        isActive && 'SceneIndicator--active',
        className
      )}
      role="status"
      aria-live="polite"
      title={label}
    >
      <span className="SceneIndicator__dot" aria-hidden="true" />
      <span className="SceneIndicator__label">{label}</span>
    </div>
  );
});
