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
  MeshNearbyStateType,
  MeshStatsStateType,
  MeshTransferStateType,
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
  /** `MeshNode_Nearby`: cards seen in the last 24 h, most recent first. */
  nearby: ReadonlyArray<MeshNearbyStateType>;
  onAddNearby: (fingerprintHex: string) => Promise<void>;
  /** Incoming attachment transfers in flight. */
  transfers: ReadonlyArray<MeshTransferStateType & { transferHex: string }>;
  /** `MeshNode_SelfTest`; resolves with the multi-line report. */
  onRunSelfTest: () => Promise<string>;
  /** Resolves with the saved path, or undefined when the dialog was cancelled. */
  onExportBackup: (passphrase: string) => Promise<string | undefined>;
  /** Resolves with the restored file's path, or undefined when cancelled. */
  onRestoreBackup: (passphrase: string) => Promise<string | undefined>;
}>;

function formatLastSeen(lastSeenAt: number): string {
  const seconds = Math.max(0, Math.round((Date.now() - lastSeenAt) / 1000));
  if (seconds < 60) {
    return 'just now';
  }
  if (seconds < 3600) {
    return `${Math.round(seconds / 60)} min ago`;
  }
  return `${Math.round(seconds / 3600)} h ago`;
}

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
      <AxoButton.Root
        variant="implied-secondary"
        size="sm"
        onClick={() => onPick(null)}
      >
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
  nearby,
  onAddNearby,
  transfers,
  onRunSelfTest,
  onExportBackup,
  onRestoreBackup,
}: PropsType): JSX.Element {
  const [pastedCard, setPastedCard] = useState('');
  const [addError, setAddError] = useState<string | undefined>();
  const [isBusy, setIsBusy] = useState(false);
  const [nearbyError, setNearbyError] = useState<string | undefined>();
  const [selfTestReport, setSelfTestReport] = useState<string | undefined>();
  const [isSelfTesting, setIsSelfTesting] = useState(false);
  const [backupPassphrase, setBackupPassphrase] = useState('');
  const [backupStatus, setBackupStatus] = useState<string | undefined>();

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

  const addNearby = useCallback(
    async (peerFingerprintHex: string) => {
      setNearbyError(undefined);
      try {
        await onAddNearby(peerFingerprintHex);
      } catch (error) {
        setNearbyError(error instanceof Error ? error.message : String(error));
      }
    },
    [onAddNearby]
  );

  const runSelfTest = useCallback(async () => {
    setIsSelfTesting(true);
    setSelfTestReport(undefined);
    // Let the button repaint before the synchronous native call parks the
    // renderer thread for up to 15 s.
    await new Promise(resolve => {
      setTimeout(resolve, 50);
    });
    let report: string;
    try {
      report = await onRunSelfTest();
    } catch (error) {
      report = `FAIL self-test could not run: ${error instanceof Error ? error.message : String(error)}`;
    }
    setSelfTestReport(report);
    setIsSelfTesting(false);
  }, [onRunSelfTest]);

  const exportBackup = useCallback(async () => {
    setBackupStatus(undefined);
    try {
      const path = await onExportBackup(backupPassphrase);
      setBackupStatus(path ? `Saved to ${path}` : 'Export cancelled');
      if (path) {
        setBackupPassphrase('');
      }
    } catch (error) {
      setBackupStatus(error instanceof Error ? error.message : String(error));
    }
  }, [backupPassphrase, onExportBackup]);

  const restoreBackup = useCallback(async () => {
    setBackupStatus(undefined);
    try {
      const path = await onRestoreBackup(backupPassphrase);
      setBackupStatus(path ? `Restored from ${path}` : 'Restore cancelled');
      if (path) {
        setBackupPassphrase('');
      }
    } catch (error) {
      setBackupStatus(error instanceof Error ? error.message : String(error));
    }
  }, [backupPassphrase, onRestoreBackup]);

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
                onChange={event =>
                  onGatewayAddressChange(event.currentTarget.value)
                }
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
              <label htmlFor="mesh-listen">
                Listen on LAN port (empty = off)
              </label>{' '}
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
              {links.length} link{links.length === 1 ? '' : 's'},{' '}
              {neighbourCount} neighbour{neighbourCount === 1 ? '' : 's'}
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
        <SettingsRow title="Nearby">
          <div className="Preferences--internal--result">
            <p>
              Devices whose card reached this one in the last 24 hours, over any
              link. Peers on the same Wi-Fi are found automatically (mDNS{' '}
              <code>_asher-mesh._tcp</code>) when LAN listening is on.
            </p>
            {nearby.length === 0 ? <p>Nobody nearby yet.</p> : null}
            <ul>
              {nearby.map(peer => (
                <li key={peer.fingerprintHex}>
                  {peer.name || 'Unnamed'} <code>{peer.fingerprintHex}</code>{' '}
                  {peer.direct ? <strong>direct</strong> : null}{' '}
                  <small>{formatLastSeen(peer.lastSeenAt)}</small>{' '}
                  {peer.isContact ? (
                    <small>contact</small>
                  ) : (
                    <AxoButton.Root
                      variant="subtle-secondary"
                      size="sm"
                      onClick={() => addNearby(peer.fingerprintHex)}
                    >
                      Add
                    </AxoButton.Root>
                  )}
                </li>
              ))}
            </ul>
            {nearbyError ? <p>{nearbyError}</p> : null}
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
                  {contact.name || 'Unnamed'}{' '}
                  <code>{contact.fingerprintHex}</code>
                </li>
              ))}
            </ul>
            {transfers.length > 0 ? (
              <ul>
                {transfers.map(transfer => (
                  <li key={transfer.transferHex}>
                    Receiving from <code>{transfer.fromHex.slice(0, 8)}</code>:{' '}
                    {transfer.received}/{transfer.total} chunks
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
        </SettingsRow>
      ) : null}

      {isRunning ? (
        <SettingsRow title="Encrypted backup">
          <div className="Preferences--internal--result">
            <p>
              Contacts, groups and carried messages, encrypted with a passphrase
              (<code>.asherbackup</code>). Restore merges them into this device;
              a backup made under another identity is refused.
            </p>
            <p>
              <label htmlFor="mesh-backup-passphrase">Passphrase</label>{' '}
              <input
                id="mesh-backup-passphrase"
                type="password"
                autoComplete="off"
                value={backupPassphrase}
                onChange={event =>
                  setBackupPassphrase(event.currentTarget.value)
                }
              />
            </p>
            <p>
              <AxoButton.Root
                variant="subtle-secondary"
                size="sm"
                pending={isBusy}
                disabled={backupPassphrase.length === 0}
                onClick={() => run(exportBackup)}
              >
                Export encrypted mesh backup
              </AxoButton.Root>{' '}
              <AxoButton.Root
                variant="subtle-secondary"
                size="sm"
                pending={isBusy}
                disabled={backupPassphrase.length === 0}
                onClick={() => run(restoreBackup)}
              >
                Restore
              </AxoButton.Root>
            </p>
            {backupStatus ? <p>{backupStatus}</p> : null}
          </div>
        </SettingsRow>
      ) : null}

      {isRunning ? (
        <SettingsRow title="Self-test">
          <div className="Preferences--internal--result">
            <p>
              Runs two in-process nodes over an in-memory link and exchanges
              cards and a text both ways. No radio, no network. The app pauses
              for up to 15 seconds while it runs.
            </p>
            <AxoButton.Root
              variant="strong-secondary"
              size="sm"
              pending={isSelfTesting}
              onClick={runSelfTest}
            >
              Run self-test
            </AxoButton.Root>
            {selfTestReport ? (
              <pre className="PreferencesMesh__report">{selfTestReport}</pre>
            ) : null}
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
