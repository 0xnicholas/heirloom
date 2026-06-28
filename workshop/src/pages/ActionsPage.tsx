import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ActionList } from '@/components/security/ActionList';
import { ActionEditor } from '@/components/security/ActionEditor';
import { useActions } from '@/hooks/useActions';
import { useSchemaRegistry } from '@/hooks/useSchemaRegistry';
import type { Action } from '@/lib/types';

export function ActionsPage() {
  const { actionName } = useParams<{ actionName?: string }>();
  const navigate = useNavigate();
  const { typesQuery } = useSchemaRegistry();
  const { actionsQuery, saveActionMutation } = useActions();
  const types = typesQuery.data || [];
  const actions = actionsQuery.data || [];

  const activeAction = actionName ? actions.find(a => a.name === actionName) || null : null;
  const [isNew, setIsNew] = useState(false);

  const handleSelect = (name: string) => {
    setIsNew(false);
    navigate(`/actions/${name}`);
  };

  const handleNew = () => {
    setIsNew(true);
    navigate('/actions');
  };

  const handleSave = (action: Action) => {
    saveActionMutation.mutate({ action, isNew });
    setIsNew(false);
    navigate(`/actions/${action.name}`);
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
    <div className="flex h-full bg-gray-50 dark:bg-gray-950">
      <div className="w-[260px] border-r border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 overflow-auto shrink-0">
        <ActionList
          actions={actions}
          selected={activeAction?.name || null}
          onSelect={handleSelect}
          onNew={handleNew}
        />
      </div>
      <div className="flex-1 overflow-auto bg-white dark:bg-gray-900">
        <ActionEditor
          key={isNew ? '__new__' : actionName ?? '__none__'}
          action={isNew ? newActionTemplate : activeAction}
          allTypes={types}
          onSave={handleSave}
          isNew={isNew}
        />
      </div>
    </div>
  );
}
