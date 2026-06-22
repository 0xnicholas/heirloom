import type { Action, Diagnostic, SchemaRegistrySnapshot } from '../types';

export function validateAction(
  action: Action,
  snapshot: SchemaRegistrySnapshot,
): Diagnostic[] {
  const diagnostics: Diagnostic[] = [];

  // Check target type exists
  const targetType = snapshot.types.get(action.targetType);
  if (!targetType) {
    diagnostics.push({
      severity: 'error',
      message: `Target type "${action.targetType}" is not registered in schema`,
    });
    return diagnostics;
  }

  // Check requires ability is declared on target type
  if (!targetType.abilities.includes(action.requires)) {
    diagnostics.push({
      severity: 'error',
      message: `Ability "${action.requires}" is not declared on type "${action.targetType}". Declared abilities: [${targetType.abilities.join(', ')}]`,
    });
  }

  // Check gate state exists in target type's state machine
  if (action.gate) {
    const states = new Set(targetType.stateMachine.flatMap(t => [t.from, t.to]));
    if (!states.has(action.gate.state)) {
      diagnostics.push({
        severity: 'error',
        message: `Gate state "${action.gate.state}" is not defined in the state machine of "${action.targetType}". Available states: [${[...states].join(', ')}]`,
      });
    }
  }

  // Check parameters: type match (error) and field existence (warning)
  const typeFieldMap = new Map(targetType.fields.map(f => [f.name, f.type]));

  for (const param of action.parameters) {
    if (typeFieldMap.has(param.name)) {
      const expectedType = typeFieldMap.get(param.name);
      if (expectedType && expectedType !== param.type) {
        diagnostics.push({
          severity: 'error',
          message: `Parameter "${param.name}" type mismatch: action declares ${param.type}, but field is ${expectedType}`,
        });
      }
    } else {
      diagnostics.push({
        severity: 'warning',
        message: `Parameter "${param.name}" is not a field on type "${action.targetType}"`,
      });
    }
  }

  return diagnostics;
}
