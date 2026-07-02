import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Box } from '@mantine/core';
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

  const activeAction = actionName ? actions.find((a) => a.name === actionName) || null : null;
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
    <Box style={{ display: 'flex', height: '100%' }}>
      <Box w={260} style={{ borderRight: '1px solid var(--mantine-color-default-border)', overflow: 'auto', flexShrink: 0 }}>
        <ActionList
          actions={actions}
          selected={activeAction?.name || null}
          onSelect={handleSelect}
          onNew={handleNew}
        />
      </Box>
      <Box style={{ flex: 1, overflow: 'auto' }}>
        <ActionEditor
          key={isNew ? '__new__' : (actionName ?? '__none__')}
          action={isNew ? newActionTemplate : activeAction}
          allTypes={types}
          onSave={handleSave}
          isNew={isNew}
        />
      </Box>
    </Box>
  );
}
