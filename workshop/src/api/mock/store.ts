import { mockTypes, mockRoles, mockActions } from './data';
import type { ResourceType, Role, Action, SavedQuery } from '@/lib/types';

const KEYS = {
  types: 'heirloom_mock_types',
  roles: 'heirloom_mock_roles',
  actions: 'heirloom_mock_actions',
  savedQueries: 'heirloom_mock_saved_queries',
};

function load<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;
  }
}

function save<T>(key: string, data: T) {
  localStorage.setItem(key, JSON.stringify(data));
}

export function getTypes(): ResourceType[] {
  return load<ResourceType[]>(KEYS.types, mockTypes);
}

export function saveTypes(types: ResourceType[]) {
  save(KEYS.types, types);
}

export function getRoles(): Role[] {
  return load<Role[]>(KEYS.roles, mockRoles);
}

export function saveRoles(roles: Role[]) {
  save(KEYS.roles, roles);
}

export function getActions(): Action[] {
  return load<Action[]>(KEYS.actions, mockActions);
}

export function saveActions(actions: Action[]) {
  save(KEYS.actions, actions);
}

export function getSavedQueries(): SavedQuery[] {
  return load<SavedQuery[]>(KEYS.savedQueries, []);
}

export function saveSavedQueries(queries: SavedQuery[]) {
  save(KEYS.savedQueries, queries);
}
