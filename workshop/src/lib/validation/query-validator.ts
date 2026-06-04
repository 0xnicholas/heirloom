import type { QueryDSL, Diagnostic, SchemaRegistrySnapshot, TraverseStep } from '../types';

function parseTraversePath(path: string): { sourceAlias: string; relationLabel: string; targetType: string } | null {
  // Parse "c --[placed]--> Order" or "c --[placed]--> Order as o"
  const match = path.match(/(\w+)\s*--\[(\w+)\]-->\s*(\w+)(?:\s+as\s+\w+)?$/);
  if (!match) return null;
  return {
    sourceAlias: match[1],
    relationLabel: match[2],
    targetType: match[3],
  };
}

function validateTraverse(
  steps: TraverseStep[] | undefined,
  fromType: string,
  snapshot: SchemaRegistrySnapshot,
): Diagnostic[] {
  const diagnostics: Diagnostic[] = [];
  if (!steps) return diagnostics;

  for (const step of steps) {
    const parsed = parseTraversePath(step.path);
    if (!parsed) {
      diagnostics.push({
        severity: 'error',
        message: `Invalid traverse path syntax: "${step.path}"`,
      });
      continue;
    }

    // Check relation label exists on source type
    const sourceType = snapshot.types.get(fromType);
    if (sourceType) {
      const rel = sourceType.relationships.find(r => r.label === parsed.relationLabel);
      if (!rel) {
        diagnostics.push({
          severity: 'error',
          message: `Relationship "${parsed.relationLabel}" not found on type "${fromType}"`,
        });
      }
    }

    // Check target type exists
    if (!snapshot.types.has(parsed.targetType)) {
      diagnostics.push({
        severity: 'error',
        message: `Target type "${parsed.targetType}" in traverse path does not exist`,
      });
    }

    // Recurse into nested traverses
    if (step.traverse) {
      diagnostics.push(...validateTraverse(step.traverse, parsed.targetType, snapshot));
    }
  }

  return diagnostics;
}

export function validateQuery(
  query: QueryDSL,
  snapshot: SchemaRegistrySnapshot,
): Diagnostic[] {
  const diagnostics: Diagnostic[] = [];

  // Check from type exists
  const fromType = snapshot.types.get(query.from);
  if (!fromType) {
    diagnostics.push({
      severity: 'error',
      message: `Type "${query.from}" does not exist in schema registry`,
    });
    return diagnostics; // Can't validate further without the type
  }

  // Check select fields
  if (query.select) {
    const fieldNames = new Set(fromType.fields.map(f => f.name));
    for (const sel of query.select) {
      // Skip meta fields and aggregates
      if (sel.startsWith('_') || sel.includes('(')) continue;
      // For traverse queries, the field may come from a traversed type
      if (!sel.includes('.')) {
        if (!fieldNames.has(sel)) {
          diagnostics.push({
            severity: 'error',
            message: `Field "${sel}" does not exist on type "${query.from}"`,
          });
        }
      }
    }
  }

  // Check filter fields
  if (query.filter) {
    const fieldNames = new Set(fromType.fields.map(f => f.name));
    for (const key of Object.keys(query.filter)) {
      if (key.startsWith('$')) continue;
      if (!fieldNames.has(key)) {
        diagnostics.push({
          severity: 'error',
          message: `Filter field "${key}" does not exist on type "${query.from}"`,
        });
      }
    }
  }

  // Check traverse paths
  if (query.traverse) {
    diagnostics.push(...validateTraverse(query.traverse, query.from, snapshot));
  }

  // Check limit
  if (query.limit !== undefined && query.limit <= 0) {
    diagnostics.push({
      severity: 'warning',
      message: `Limit should be positive, got ${query.limit}`,
    });
  }

  return diagnostics;
}
