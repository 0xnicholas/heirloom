import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { RoleList } from '@/components/security/RoleList';
import { ActionList } from '@/components/security/ActionList';
import { RoleEditor } from '@/components/security/RoleEditor';
import { ActionEditor } from '@/components/security/ActionEditor';
import { useSecurity } from '@/hooks/useSecurity';
import { useSchemaRegistry } from '@/hooks/useSchemaRegistry';
import type { Role, Action } from '@/lib/types';

export function SecurityPage() {
  const { roleName, actionName } = useParams<{ roleName?: string; actionName?: string }>();
  const navigate = useNavigate();
  const { typesQuery } = useSchemaRegistry();
  const { rolesQuery, actionsQuery, saveRoleMutation, saveActionMutation } = useSecurity();
  const types = typesQuery.data || [];
  const roles = rolesQuery.data || [];
  const actions = actionsQuery.data || [];

  // Determine which editor to show
  const activeRole = roleName ? roles.find(r => r.name === roleName) || null : null;
  const activeAction = actionName ? actions.find(a => a.name === actionName) || null : null;
  const [isNewRole, setIsNewRole] = useState(false);
  const [isNewAction, setIsNewAction] = useState(false);

  const handleSelectRole = (name: string) => {
    setIsNewRole(false);
    setIsNewAction(false);
    navigate(`/security/roles/${name}`);
  };

  const handleNewRole = () => {
    setIsNewRole(true);
    setIsNewAction(false);
    navigate('/security');
  };

  const handleSaveRole = (role: Role) => {
    saveRoleMutation.mutate({ role, isNew: isNewRole });
    setIsNewRole(false);
    navigate(`/security/roles/${role.name}`);
  };

  const handleSelectAction = (name: string) => {
    setIsNewRole(false);
    setIsNewAction(false);
    navigate(`/security/actions/${name}`);
  };

  const handleNewAction = () => {
    setIsNewRole(false);
    setIsNewAction(true);
    navigate('/security');
  };

  const handleSaveAction = (action: Action) => {
    saveActionMutation.mutate({ action, isNew: isNewAction });
    setIsNewAction(false);
    navigate(`/security/actions/${action.name}`);
  };

  const newRoleTemplate: Role = {
    name: '',
    scope: 'Type',
    targets: [],
    capabilities: [],
    actors: [],
  };

  const newActionTemplate: Action = {
    name: '',
    targetType: types[0]?.name || '',
    requires: types[0]?.abilities[0] || 'query',
    parameters: [],
    validateRules: [],
    executeTemplate: '',
  };

  return (
    <div className="flex h-full">
      {/* Left sidebar: two collapsible sections */}
      <div className="w-[260px] border-r border-gray-200 bg-white overflow-auto shrink-0">
        <details open>
          <summary className="px-3 py-2 text-xs font-semibold text-gray-500 uppercase cursor-pointer hover:bg-gray-50">
            Roles
          </summary>
          <RoleList
            roles={roles}
            selected={activeRole?.name || null}
            onSelect={handleSelectRole}
            onNew={handleNewRole}
          />
        </details>
        <details>
          <summary className="px-3 py-2 text-xs font-semibold text-gray-500 uppercase cursor-pointer border-t hover:bg-gray-50">
            Actions
          </summary>
          <ActionList
            actions={actions}
            selected={activeAction?.name || null}
            onSelect={handleSelectAction}
            onNew={handleNewAction}
          />
        </details>
      </div>
      {/* Right editor: Role or Action depending on context */}
      <div className="flex-1 overflow-auto">
        {activeRole || isNewRole ? (
          <RoleEditor
            role={isNewRole ? newRoleTemplate : activeRole}
            allTypes={types}
            onSave={handleSaveRole}
            isNew={isNewRole}
          />
        ) : activeAction || isNewAction ? (
          <ActionEditor
            action={isNewAction ? newActionTemplate : activeAction}
            allTypes={types}
            onSave={handleSaveAction}
            isNew={isNewAction}
          />
        ) : (
          <div className="flex items-center justify-center h-full text-gray-400">
            Select a role or action to edit
          </div>
        )}
      </div>
    </div>
  );
}
