import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Box } from '@mantine/core';
import { RoleList } from '@/components/security/RoleList';
import { RoleEditor } from '@/components/security/RoleEditor';
import { useRoles } from '@/hooks/useRoles';
import { useSchemaRegistry } from '@/hooks/useSchemaRegistry';
import type { Role } from '@/lib/types';

export function RolesPage() {
  const { roleName } = useParams<{ roleName?: string }>();
  const navigate = useNavigate();
  const { typesQuery } = useSchemaRegistry();
  const { rolesQuery, saveRoleMutation } = useRoles();
  const types = typesQuery.data || [];
  const roles = rolesQuery.data || [];

  const activeRole = roleName ? roles.find((r) => r.name === roleName) || null : null;
  const [isNew, setIsNew] = useState(false);

  const handleSelect = (name: string) => {
    setIsNew(false);
    navigate(`/roles/${name}`);
  };

  const handleNew = () => {
    setIsNew(true);
    navigate('/roles');
  };

  const handleSave = (role: Role) => {
    saveRoleMutation.mutate({ role, isNew });
    setIsNew(false);
    navigate(`/roles/${role.name}`);
  };

  const newRoleTemplate: Role = {
    name: '',
    scope: 'Type',
    targets: [],
    capabilities: [],
    actors: [],
  };

  return (
    <Box style={{ display: 'flex', height: '100%' }}>
      <Box w={260} style={{ borderRight: '1px solid var(--mantine-color-default-border)', overflow: 'auto', flexShrink: 0 }}>
        <RoleList
          roles={roles}
          selected={activeRole?.name || null}
          onSelect={handleSelect}
          onNew={handleNew}
        />
      </Box>
      <Box style={{ flex: 1, overflow: 'auto' }}>
        <RoleEditor
          key={isNew ? '__new__' : (roleName ?? '__none__')}
          role={isNew ? newRoleTemplate : activeRole}
          allTypes={types}
          onSave={handleSave}
          isNew={isNew}
        />
      </Box>
    </Box>
  );
}
