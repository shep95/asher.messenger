// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

import { useCallback, useState, type JSX } from 'react';

import { AxoButton } from '../axo/AxoButton.dom.tsx';
import { AxoSwitch } from '../axo/AxoSwitch.dom.tsx';
import { QrCode } from './QrCode.dom.tsx';
import { FlowingSettingsControl, SettingsRow } from './PreferencesUtil.dom.tsx';

import type {
  MeshContactStateType,
  MeshDeviceChoiceType,
  MeshLinkStateType,
  MeshStatsStateType,
} from '../state/ducks/mesh.std.ts';

// Labels are plain English for now, like SceneIndicator: the mesh strings
// join _locales once the transport leaves the flag.

export type PropsType = Readonly<{
  isEnabled: boolean;
  isRunning: boolean;
  onToggle: (enabled: boolean) => void;
  fingerprintHex: string | undefined;
  cardBase64: string | undefined;
  contacts: ReadonlyArray<MeshContactStateType>;
  links: ReadonlyArray<MeshLinkStateType>;
  neighbourCount: number;
  stats: MeshStatsStateType;
  gatewayAddress: string;
  onGatewayAddressChange: (value: string) => void;
  onConnectGateway: () => Promise<void>;
  listenPort: number | undefined;
  onListenPortChange: (value: number | undefined) => void;
  onAddCard: (cardBase64: string) => Promise<void>;
  onOpenRadio: () => Promise<void>;
  onOpenBluetooth: () => Promise<void>;
  serialPortChoices: ReadonlyArray<MeshDeviceChoiceType> | null;
  bluetoothDeviceChoices: ReadonlyArray<MeshDeviceChoiceType> | null;
  onPickSerialPort: (id: string | null) => void;
  onPickBluetoothDevice: (id: string | null) => void;
  lastError: string | undefined;
}>;

function DeviceChooser({
  title,
  choices,
  onPick,
}: {
  title: string;
  choices: ReadonlyArray<MeshDeviceChoiceType>;
  onPick: (id: string | null) => void;
}): JSX.Element {
  return (
    <div className="Preferences--internal--result">
      <p>{title}</p>
      {choices.length === 0 ? <p>Searching…</p> : null}
      <ul>
        {choices.map(choice => (
          <li key={choice.id}>
            <AxoButton.Root
              variant="subtle-secondary"
              size="sm"
              onClick={() => onPick(choice.id)}
            >
              {choice.label}
            </AxoButton.Root>
          </li>
        ))}
      </ul>
      <AxoButton.Root variant="implied-secondary" size="sm" onClick={() => onPick(null)}>
        Cancel
      </AxoButton.Root>
    </div>
  );
}

export function PreferencesMesh({
  isEnabled,
  isRunning,
  onToggle,
  fingerprintHex,
  cardBase64,
  contacts,
  links,
  neighbourCount,
  stats,
  gatewayAddress,
  onGatewayAddressChange,
  onConnectGateway,
  listenPort,
  onListenPortChange,
  onAddCard,
  onOpenRadio,
  onOpenBluetooth,
  serialPortChoices,
  bluetoothDeviceChoices,
  onPickSerialPort,
  onPickBluetoothDevice,
  lastError,
}: PropsType): JSX.Element {
  const [pastedCard, setPastedCard] = useState('');
  const [addError, setAddError] = useState<string | undefined>();
  const [isBusy, setIsBusy] = useState(false);

  const run = useCallback(async (task: () => Promise<void>) => {
    setIsBusy(true);
    try {
      await task();
    } finally {
      setIsBusy(false);
    }
  }, []);

  const addCard = useCallback(async () => {
    setAddError(undefined);
    try {
      await onAddCard(pastedCard);
      setPastedCard('');
    } catch (error) {
      setAddError(error instanceof Error ? error.message : String(error));
    }
  }, [onAddCard, pastedCard]);

  return (
    <>
      <SettingsRow title="Offline mesh">
        <FlowingSettingsControl>
          <label htmlFor="mesh-toggle">
            Carry messages over radios, Bluetooth and gateways when there is no
            internet (mesh.transport)
          </label>
          <AxoSwitch.Root
            id="mesh-toggle"
            checked={isEnabled}
            onCheckedChange={onToggle}
          />
        </FlowingSettingsControl>
        {lastError ? (
          <div className="Preferences--internal--result">
            <p>{lastError}</p>
          </div>
        ) : null}
      </SettingsRow>

      {isRunning && cardBase64 && fingerprintHex ? (
        <SettingsRow title="My card">
          <div className="Preferences--internal--result">
            <QrCode
              alt="Your mesh contact card"
              className="PreferencesMesh__qr"
              data={cardBase64}
            />
            <p>
              Fingerprint: <code>{fingerprintHex}</code>
            </p>
            <p>
              <textarea
                readOnly
                rows={3}
                value={cardBase64}
                onFocus={event => event.currentTarget.select()}
              />
            </p>
          </div>
        </SettingsRow>
      ) : null}

      {isRunning ? (
        <SettingsRow title="Add a contact">
          <div className="Preferences--internal--result">
            <p>
              <textarea
                rows={3}
                placeholder="Paste a contact card (base64)"
                value={pastedCard}
                onChange={event => setPastedCard(event.currentTarget.value)}
              />
            </p>
            <AxoButton.Root
              variant="strong-secondary"
              size="lg"
              disabled={pastedCard.trim().length === 0}
              onClick={addCard}
            >
              Add contact
            </AxoButton.Root>
            {addError ? <p>{addError}</p> : null}
          </div>
        </SettingsRow>
      ) : null}

      {isRunning ? (
        <SettingsRow title="Links">
          <div className="Preferences--internal--result">
            <p>
              <label htmlFor="mesh-gateway">Gateway (host:port)</label>{' '}
              <input
                id="mesh-gateway"
                type="text"
                value={gatewayAddress}
                placeholder="gateway.example:48120"
                onChange={event => onGatewayAddressChange(event.currentTarget.value)}
              />{' '}
              <AxoButton.Root
                variant="subtle-secondary"
                size="sm"
                pending={isBusy}
                disabled={gatewayAddress.trim().length === 0}
                onClick={() => run(onConnectGateway)}
              >
                Connect
              </AxoButton.Root>
            </p>
            <p>
              <label htmlFor="mesh-listen">Listen on LAN port (empty = off)</label>{' '}
              <input
                id="mesh-listen"
                type="number"
                min={1}
                max={65535}
                value={listenPort ?? ''}
                onChange={event => {
                  const value = event.currentTarget.value.trim();
                  onListenPortChange(value ? Number(value) : undefined);
                }}
              />
            </p>
            <p>
              <AxoButton.Root
                variant="subtle-secondary"
                size="sm"
                pending={isBusy}
                onClick={() => run(onOpenRadio)}
              >
                Connect RNode radio (serial)
              </AxoButton.Root>{' '}
              <AxoButton.Root
                variant="subtle-secondary"
                size="sm"
                pending={isBusy}
                onClick={() => run(onOpenBluetooth)}
              >
                Connect phone (Bluetooth)
              </AxoButton.Root>
            </p>
            {serialPortChoices ? (
              <DeviceChooser
                title="Pick the radio's serial port"
                choices={serialPortChoices}
                onPick={onPickSerialPort}
              />
            ) : null}
            {bluetoothDeviceChoices ? (
              <DeviceChooser
                title="Pick the device"
                choices={bluetoothDeviceChoices}
                onPick={onPickBluetoothDevice}
              />
            ) : null}
            <p>
              {links.length} link{links.length === 1 ? '' : 's'}, {neighbourCount}{' '}
              neighbour{neighbourCount === 1 ? '' : 's'}
            </p>
            <ul>
              {links.map(link => (
                <li key={link.key}>
                  {link.kind}: {link.label}
                </li>
              ))}
            </ul>
          </div>
        </SettingsRow>
      ) : null}

      {isRunning ? (
        <SettingsRow title="Mesh contacts">
          <div className="Preferences--internal--result">
            {contacts.length === 0 ? <p>No mesh contacts yet.</p> : null}
            <ul>
              {contacts.map(contact => (
                <li key={contact.fingerprintHex}>
                  {contact.name || 'Unnamed'} <code>{contact.fingerprintHex}</code>
                </li>
              ))}
            </ul>
          </div>
        </SettingsRow>
      ) : null}

      {isRunning ? (
        <SettingsRow title="Link statistics">
          <div className="Preferences--internal--result">
            <pre>
              <table>
                <tbody>
                  {Object.entries(stats).map(([key, value]) => (
                    <tr key={key}>
                      <td>{key}</td>
                      <td>{value}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </pre>
          </div>
        </SettingsRow>
      ) : null}
    </>
  );
}
