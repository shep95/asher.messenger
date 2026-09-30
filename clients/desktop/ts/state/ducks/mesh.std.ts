// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// Redux slice for the offline mesh transport: what the scene indicator and
// Preferences > Mesh show. MeshService is the only writer.

import type { ReadonlyDeep } from 'type-fest';

import { useBoundActions } from '../../hooks/useBoundActions.std.ts';

import type { BoundActionCreatorsMapObject } from '../../hooks/useBoundActions.std.ts';

// State

export type MeshLinkKind = 'tcp' | 'serial' | 'ble';

export type MeshLinkStateType = ReadonlyDeep<{
  key: string;
  kind: MeshLinkKind;
  label: string;
}>;

export type MeshContactStateType = ReadonlyDeep<{
  conversationId: string;
  fingerprintHex: string;
  name: string;
  addedAt: number;
}>;

/** Counters as numbers (the bridge gives u64 bigints; redux wants plain data). */
export type MeshStatsStateType = ReadonlyDeep<Record<string, number>>;

export type MeshDeviceChoiceType = ReadonlyDeep<{ id: string; label: string }>;

/** One `MeshNode_Nearby` entry: a card seen on the mesh in the last 24 h. */
export type MeshNearbyStateType = ReadonlyDeep<{
  fingerprintHex: string;
  name: string;
  /** Milliseconds since the epoch. */
  lastSeenAt: number;
  /** A current neighbour (one hop away). */
  direct: boolean;
  /** Already a saved mesh contact. */
  isContact: boolean;
}>;

/** An incoming attachment transfer (`Event.AttachmentProgress`). */
export type MeshTransferStateType = ReadonlyDeep<{
  fromHex: string;
  received: number;
  total: number;
}>;

export type MeshStateType = ReadonlyDeep<{
  isRunning: boolean;
  fingerprintHex: string | undefined;
  cardBase64: string | undefined;
  links: Record<string, MeshLinkStateType>;
  /** fingerprintHex -> link key of the neighbour that announced it. */
  neighbours: Record<string, string>;
  contactsByConversationId: Record<string, MeshContactStateType>;
  /** conversationId -> bundles sent but not yet acknowledged. */
  carryingByConversationId: Record<string, number>;
  stats: MeshStatsStateType;
  serialPortChoices: ReadonlyArray<MeshDeviceChoiceType> | null;
  bluetoothDeviceChoices: ReadonlyArray<MeshDeviceChoiceType> | null;
  lastError: string | undefined;
  nearby: ReadonlyArray<MeshNearbyStateType>;
  /** transfer id (hex) -> progress; removed once complete. */
  transfersById: Record<string, MeshTransferStateType>;
}>;

// Actions

const SET_RUNNING = 'mesh/SET_RUNNING';
const SET_IDENTITY = 'mesh/SET_IDENTITY';
const LINK_ATTACHED = 'mesh/LINK_ATTACHED';
const LINK_DETACHED = 'mesh/LINK_DETACHED';
const NEIGHBOUR_SEEN = 'mesh/NEIGHBOUR_SEEN';
const SET_CONTACTS = 'mesh/SET_CONTACTS';
const UPSERT_CONTACT = 'mesh/UPSERT_CONTACT';
const SET_CARRYING = 'mesh/SET_CARRYING';
const SET_STATS = 'mesh/SET_STATS';
const SET_SERIAL_PORT_CHOICES = 'mesh/SET_SERIAL_PORT_CHOICES';
const SET_BLUETOOTH_DEVICE_CHOICES = 'mesh/SET_BLUETOOTH_DEVICE_CHOICES';
const SET_LAST_ERROR = 'mesh/SET_LAST_ERROR';
const SET_NEARBY = 'mesh/SET_NEARBY';
const SET_TRANSFER_PROGRESS = 'mesh/SET_TRANSFER_PROGRESS';

type SetRunningAction = ReadonlyDeep<{
  type: typeof SET_RUNNING;
  payload: { isRunning: boolean };
}>;
type SetIdentityAction = ReadonlyDeep<{
  type: typeof SET_IDENTITY;
  payload: { fingerprintHex: string; cardBase64: string };
}>;
type LinkAttachedAction = ReadonlyDeep<{
  type: typeof LINK_ATTACHED;
  payload: MeshLinkStateType;
}>;
type LinkDetachedAction = ReadonlyDeep<{
  type: typeof LINK_DETACHED;
  payload: { key: string };
}>;
type NeighbourSeenAction = ReadonlyDeep<{
  type: typeof NEIGHBOUR_SEEN;
  payload: { key: string; fingerprintHex: string };
}>;
type SetContactsAction = ReadonlyDeep<{
  type: typeof SET_CONTACTS;
  payload: { contacts: ReadonlyArray<MeshContactStateType> };
}>;
type UpsertContactAction = ReadonlyDeep<{
  type: typeof UPSERT_CONTACT;
  payload: MeshContactStateType;
}>;
type SetCarryingAction = ReadonlyDeep<{
  type: typeof SET_CARRYING;
  payload: { conversationId: string; count: number };
}>;
type SetStatsAction = ReadonlyDeep<{
  type: typeof SET_STATS;
  payload: { stats: MeshStatsStateType };
}>;
type SetSerialPortChoicesAction = ReadonlyDeep<{
  type: typeof SET_SERIAL_PORT_CHOICES;
  payload: { choices: ReadonlyArray<MeshDeviceChoiceType> | null };
}>;
type SetBluetoothDeviceChoicesAction = ReadonlyDeep<{
  type: typeof SET_BLUETOOTH_DEVICE_CHOICES;
  payload: { choices: ReadonlyArray<MeshDeviceChoiceType> | null };
}>;
type SetLastErrorAction = ReadonlyDeep<{
  type: typeof SET_LAST_ERROR;
  payload: { lastError: string | undefined };
}>;
type SetNearbyAction = ReadonlyDeep<{
  type: typeof SET_NEARBY;
  payload: { nearby: ReadonlyArray<MeshNearbyStateType> };
}>;
type SetTransferProgressAction = ReadonlyDeep<{
  type: typeof SET_TRANSFER_PROGRESS;
  payload: { transferHex: string; progress: MeshTransferStateType | undefined };
}>;

export type MeshActionType = ReadonlyDeep<
  | SetRunningAction
  | SetIdentityAction
  | LinkAttachedAction
  | LinkDetachedAction
  | NeighbourSeenAction
  | SetContactsAction
  | UpsertContactAction
  | SetCarryingAction
  | SetStatsAction
  | SetSerialPortChoicesAction
  | SetBluetoothDeviceChoicesAction
  | SetLastErrorAction
  | SetNearbyAction
  | SetTransferProgressAction
>;

// Action Creators

function setMeshRunning(isRunning: boolean): SetRunningAction {
  return { type: SET_RUNNING, payload: { isRunning } };
}

function setMeshIdentity(
  fingerprintHex: string,
  cardBase64: string
): SetIdentityAction {
  return { type: SET_IDENTITY, payload: { fingerprintHex, cardBase64 } };
}

function meshLinkAttached(link: MeshLinkStateType): LinkAttachedAction {
  return { type: LINK_ATTACHED, payload: link };
}

function meshLinkDetached(key: string): LinkDetachedAction {
  return { type: LINK_DETACHED, payload: { key } };
}

function meshNeighbourSeen(
  key: string,
  fingerprintHex: string
): NeighbourSeenAction {
  return { type: NEIGHBOUR_SEEN, payload: { key, fingerprintHex } };
}

function setMeshContacts(
  contacts: ReadonlyArray<MeshContactStateType>
): SetContactsAction {
  return { type: SET_CONTACTS, payload: { contacts } };
}

function upsertMeshContact(contact: MeshContactStateType): UpsertContactAction {
  return { type: UPSERT_CONTACT, payload: contact };
}

function setMeshCarrying(
  conversationId: string,
  count: number
): SetCarryingAction {
  return { type: SET_CARRYING, payload: { conversationId, count } };
}

function setMeshStats(stats: MeshStatsStateType): SetStatsAction {
  return { type: SET_STATS, payload: { stats } };
}

function setMeshSerialPortChoices(
  choices: ReadonlyArray<MeshDeviceChoiceType> | null
): SetSerialPortChoicesAction {
  return { type: SET_SERIAL_PORT_CHOICES, payload: { choices } };
}

function setMeshBluetoothDeviceChoices(
  choices: ReadonlyArray<MeshDeviceChoiceType> | null
): SetBluetoothDeviceChoicesAction {
  return { type: SET_BLUETOOTH_DEVICE_CHOICES, payload: { choices } };
}

function setMeshLastError(lastError: string | undefined): SetLastErrorAction {
  return { type: SET_LAST_ERROR, payload: { lastError } };
}

function setMeshNearby(
  nearby: ReadonlyArray<MeshNearbyStateType>
): SetNearbyAction {
  return { type: SET_NEARBY, payload: { nearby } };
}

/** `undefined` progress (or received >= total) removes the entry. */
function setMeshTransferProgress(
  transferHex: string,
  progress: MeshTransferStateType | undefined
): SetTransferProgressAction {
  return { type: SET_TRANSFER_PROGRESS, payload: { transferHex, progress } };
}

export const actions = {
  setMeshRunning,
  setMeshIdentity,
  meshLinkAttached,
  meshLinkDetached,
  meshNeighbourSeen,
  setMeshContacts,
  upsertMeshContact,
  setMeshCarrying,
  setMeshStats,
  setMeshSerialPortChoices,
  setMeshBluetoothDeviceChoices,
  setMeshLastError,
  setMeshNearby,
  setMeshTransferProgress,
};

export const useMeshActions = (): BoundActionCreatorsMapObject<
  typeof actions
> => useBoundActions(actions);

// Reducer

export function getEmptyState(): MeshStateType {
  return {
    isRunning: false,
    fingerprintHex: undefined,
    cardBase64: undefined,
    links: {},
    neighbours: {},
    contactsByConversationId: {},
    carryingByConversationId: {},
    stats: {},
    serialPortChoices: null,
    bluetoothDeviceChoices: null,
    lastError: undefined,
    nearby: [],
    transfersById: {},
  };
}

function withoutKey<T>(
  record: Readonly<Record<string, T>>,
  key: string
): Record<string, T> {
  const { [key]: _removed, ...rest } = record;
  return rest;
}

export function reducer(
  state: Readonly<MeshStateType> = getEmptyState(),
  action: Readonly<MeshActionType>
): MeshStateType {
  switch (action.type) {
    case SET_RUNNING:
      if (state.isRunning === action.payload.isRunning) {
        return state;
      }
      return action.payload.isRunning
        ? { ...state, isRunning: true }
        : {
            ...state,
            isRunning: false,
            links: {},
            neighbours: {},
            nearby: [],
            transfersById: {},
          };
    case SET_IDENTITY:
      return {
        ...state,
        fingerprintHex: action.payload.fingerprintHex,
        cardBase64: action.payload.cardBase64,
      };
    case LINK_ATTACHED:
      return {
        ...state,
        links: { ...state.links, [action.payload.key]: action.payload },
      };
    case LINK_DETACHED: {
      const neighbours: Record<string, string> = {};
      for (const [fingerprintHex, key] of Object.entries(state.neighbours)) {
        if (key !== action.payload.key) {
          neighbours[fingerprintHex] = key;
        }
      }
      return {
        ...state,
        links: withoutKey(state.links, action.payload.key),
        neighbours,
      };
    }
    case NEIGHBOUR_SEEN:
      if (
        state.neighbours[action.payload.fingerprintHex] === action.payload.key
      ) {
        return state;
      }
      return {
        ...state,
        neighbours: {
          ...state.neighbours,
          [action.payload.fingerprintHex]: action.payload.key,
        },
      };
    case SET_CONTACTS: {
      const contactsByConversationId: Record<string, MeshContactStateType> = {};
      for (const contact of action.payload.contacts) {
        contactsByConversationId[contact.conversationId] = contact;
      }
      return { ...state, contactsByConversationId };
    }
    case UPSERT_CONTACT:
      return {
        ...state,
        contactsByConversationId: {
          ...state.contactsByConversationId,
          [action.payload.conversationId]: action.payload,
        },
      };
    case SET_CARRYING: {
      const { conversationId, count } = action.payload;
      if ((state.carryingByConversationId[conversationId] ?? 0) === count) {
        return state;
      }
      return {
        ...state,
        carryingByConversationId:
          count > 0
            ? { ...state.carryingByConversationId, [conversationId]: count }
            : withoutKey(state.carryingByConversationId, conversationId),
      };
    }
    case SET_STATS:
      return { ...state, stats: action.payload.stats };
    case SET_SERIAL_PORT_CHOICES:
      return { ...state, serialPortChoices: action.payload.choices };
    case SET_BLUETOOTH_DEVICE_CHOICES:
      return { ...state, bluetoothDeviceChoices: action.payload.choices };
    case SET_LAST_ERROR:
      return { ...state, lastError: action.payload.lastError };
    case SET_NEARBY:
      return { ...state, nearby: action.payload.nearby };
    case SET_TRANSFER_PROGRESS: {
      const { transferHex, progress } = action.payload;
      if (!progress || progress.received >= progress.total) {
        if (!(transferHex in state.transfersById)) {
          return state;
        }
        return {
          ...state,
          transfersById: withoutKey(state.transfersById, transferHex),
        };
      }
      return {
        ...state,
        transfersById: { ...state.transfersById, [transferHex]: progress },
      };
    }
    default:
      return state;
  }
}
