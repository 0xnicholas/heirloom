import type { ResourceType, Diagnostic, SchemaRegistrySnapshot } from '../types';

export function validateType(
  type: ResourceType,
  snapshot: SchemaRegistrySnapshot,
): Diagnostic[] {
  const diagnostics: Diagnostic[] = [];

  // Check duplicate field names
  const fieldNames = type.fields.map(f => f.name);
  const seen = new Set<string>();
  for (const name of fieldNames) {
    if (seen.has(name)) {
      diagnostics.push({ severity: 'error', message: `Duplicate field name: "${name}"` });
    }
    seen.add(name);
  }

  // Check relationship target types exist
  for (const rel of type.relationships) {
    if (!snapshot.types.has(rel.targetType)) {
      diagnostics.push({
        severity: 'error',
        message: `Relationship "${rel.label}" references non-existent type "${rel.targetType}"`,
      });
    }
  }

  // Warning: orphan nodes (states with no incoming edges, excluding the initial state)
  const allStates = new Set(type.stateMachine.flatMap(t => [t.from, t.to]));
  const initialStates = new Set(type.stateMachine.map(t => t.from).filter(
    from => !type.stateMachine.some(t => t.to === from)
  ));
  const hasIncoming = new Set(type.stateMachine.map(t => t.to));
  for (const state of allStates) {
    if (!hasIncoming.has(state) && !initialStates.has(state)) {
      diagnostics.push({
        severity: 'warning',
        message: `State "${state}" has no incoming transitions (orphan node)`,
      });
    }
  }

  // Warning: no abilities declared
  if (type.abilities.length === 0) {
    diagnostics.push({
      severity: 'warning',
      message: `Type "${type.name}" has no abilities declared — it cannot be queried or modified`,
    });
  }

  // Info: PascalCase naming convention
  if (type.name[0] !== type.name[0].toUpperCase()) {
    diagnostics.push({
      severity: 'info',
      message: `Type name "${type.name}" should use PascalCase by convention`,
    });
  }

  return diagnostics;
}
