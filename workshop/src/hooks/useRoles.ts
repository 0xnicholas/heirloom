import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { Role } from '@/lib/types';
import { notifyError, notifySuccess } from '@/lib/notifications';

async function fetchRoles(): Promise<Role[]> {
  const res = await fetch('/api/roles');
  if (!res.ok) throw new Error('Failed to fetch roles');
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

export function useRoles() {
  const qc = useQueryClient();

  const rolesQuery = useQuery({
    queryKey: ['roles'],
    queryFn: fetchRoles,
  });

  const saveRoleMutation = useMutation({
    mutationFn: ({ role, isNew }: { role: Role; isNew: boolean }) =>
      saveRole(role, isNew),
    onSuccess: (_data, vars) => {
      qc.invalidateQueries({ queryKey: ['roles'] });
      notifySuccess(vars.isNew ? 'Role created' : 'Role updated');
    },
    onError: (err) => notifyError('Failed to save role', err),
  });

  const deleteRoleMutation = useMutation({
    mutationFn: (name: string) => deleteRole(name),
    onSuccess: (_data, name) => {
      qc.invalidateQueries({ queryKey: ['roles'] });
      notifySuccess(`Role "${name}" deleted`);
    },
    onError: (err) => notifyError('Failed to delete role', err),
  });

  return {
    rolesQuery,
    saveRoleMutation,
    deleteRoleMutation,
  };
}
