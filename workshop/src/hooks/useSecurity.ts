import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { Role, Action } from '@/lib/types';

async function fetchRoles(): Promise<Role[]> {
  const res = await fetch('/api/roles');
  if (!res.ok) throw new Error('Failed to fetch roles');
  return res.json();
}

async function fetchActions(): Promise<Action[]> {
  const res = await fetch('/api/actions');
  if (!res.ok) throw new Error('Failed to fetch actions');
  return res.json();
}

async function saveRole(role: Role, isNew: boolean): Promise<void> {
  const url = isNew ? '/api/roles' : `/api/roles/${role.name}`;
  const res = await fetch(url, {
    method: isNew ? 'POST' : 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(role),
  });
  if (!res.ok) throw new Error('Failed to save role');
}

async function deleteRole(name: string): Promise<void> {
  await fetch(`/api/roles/${name}`, { method: 'DELETE' });
}

async function saveAction(action: Action, isNew: boolean): Promise<void> {
  const url = isNew ? '/api/actions' : `/api/actions/${action.name}`;
  const res = await fetch(url, {
    method: isNew ? 'POST' : 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(action),
  });
  if (!res.ok) throw new Error('Failed to save action');
}

async function deleteAction(name: string): Promise<void> {
  await fetch(`/api/actions/${name}`, { method: 'DELETE' });
}

export function useSecurity() {
  const qc = useQueryClient();

  const rolesQuery = useQuery({
    queryKey: ['roles'],
    queryFn: fetchRoles,
  });

  const actionsQuery = useQuery({
    queryKey: ['actions'],
    queryFn: fetchActions,
  });

  const saveRoleMutation = useMutation({
    mutationFn: ({ role, isNew }: { role: Role; isNew: boolean }) =>
      saveRole(role, isNew),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['roles'] }),
  });

  const deleteRoleMutation = useMutation({
    mutationFn: (name: string) => deleteRole(name),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['roles'] }),
  });

  const saveActionMutation = useMutation({
    mutationFn: ({ action, isNew }: { action: Action; isNew: boolean }) =>
      saveAction(action, isNew),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['actions'] }),
  });

  const deleteActionMutation = useMutation({
    mutationFn: (name: string) => deleteAction(name),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['actions'] }),
  });

  return {
    rolesQuery,
    actionsQuery,
    saveRoleMutation,
    deleteRoleMutation,
    saveActionMutation,
    deleteActionMutation,
  };
}
