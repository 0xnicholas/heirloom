import type { ResourceType, Action, Role, SchemaRegistrySnapshot } from '../types';

export function createSnapshot(
  types: ResourceType[],
  actions: Action[],
  roles: Role[],
): SchemaRegistrySnapshot {
  const typeMap = new Map<string, ResourceType>();
  for (const t of types) {
    typeMap.set(t.name, t);
  }
  const actionMap = new Map<string, Action>();
  for (const a of actions) {
    actionMap.set(a.name, a);
  }
  const roleMap = new Map<string, Role>();
  for (const r of roles) {
    roleMap.set(r.name, r);
  }
  return { types: typeMap, actions: actionMap, roles: roleMap };
}

export function updateTypeInSnapshot(
  snapshot: SchemaRegistrySnapshot,
  type: ResourceType,
): SchemaRegistrySnapshot {
  const newTypes = new Map(snapshot.types);
  newTypes.set(type.name, type);
  return { ...snapshot, types: newTypes };
}

export function updateActionInSnapshot(
  snapshot: SchemaRegistrySnapshot,
  action: Action,
): SchemaRegistrySnapshot {
  const newActions = new Map(snapshot.actions);
  newActions.set(action.name, action);
  return { ...snapshot, actions: newActions };
}

export function updateRoleInSnapshot(
  snapshot: SchemaRegistrySnapshot,
  role: Role,
): SchemaRegistrySnapshot {
  const newRoles = new Map(snapshot.roles);
  newRoles.set(role.name, role);
  return { ...snapshot, roles: newRoles };
}
