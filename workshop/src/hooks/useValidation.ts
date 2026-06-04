import { useMemo, useState, useEffect, useRef } from 'react';
import { createSnapshot } from '@/lib/validation/registry-snapshot';
import { validateType } from '@/lib/validation/type-validator';
import { validateAction } from '@/lib/validation/action-validator';
import type {
  ResourceType,
  Action,
  SchemaRegistrySnapshot,
  Diagnostic,
} from '@/lib/types';

export function useRegistrySnapshot(
  types: ResourceType[],
  actions: Action[],
): SchemaRegistrySnapshot {
  return useMemo(() => createSnapshot(types, actions, []), [types, actions]);
}

export function useDebouncedTypeValidation(
  type: ResourceType | null,
  snapshot: SchemaRegistrySnapshot,
  delay = 300,
): Diagnostic[] {
  const [diagnostics, setDiagnostics] = useState<Diagnostic[]>([]);
  const timerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      if (!type) {
        setDiagnostics([]);
      } else {
        setDiagnostics(validateType(type, snapshot));
      }
    }, delay);
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [type, snapshot, delay]);

  return diagnostics;
}

export function useDebouncedActionValidation(
  action: Action | null,
  snapshot: SchemaRegistrySnapshot,
  delay = 300,
): Diagnostic[] {
  const [diagnostics, setDiagnostics] = useState<Diagnostic[]>([]);
  const timerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      if (!action) {
        setDiagnostics([]);
      } else {
        setDiagnostics(validateAction(action, snapshot));
      }
    }, delay);
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [action, snapshot, delay]);

  return diagnostics;
}
