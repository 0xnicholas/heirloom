import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Box } from '@mantine/core';
import { TypeList } from '@/components/schema/TypeList';
import { TypeEditor } from '@/components/schema/TypeEditor';
import { useSchemaRegistry } from '@/hooks/useSchemaRegistry';
import { useConsoleContext } from '@/components/layout/ConsoleContext';
import type { ResourceType } from '@/lib/types';

export function SchemaPage() {
  const { typeName } = useParams<{ typeName?: string }>();
  const navigate = useNavigate();
  const { typesQuery, saveMutation } = useSchemaRegistry();
  const [selectedName, setSelectedName] = useState<string | null>(typeName || null);
  const [isNew, setIsNew] = useState(false);
  const { setActiveType } = useConsoleContext();

  const types = typesQuery.data || [];
  const selectedType = types.find((t) => t.name === selectedName) || null;

  // Keep ConsoleContext in sync for QueryConsole defaultFrom
  useEffect(() => {
    setActiveType(selectedName);
  }, [selectedName, setActiveType]);

  const handleSelect = (name: string) => {
    setSelectedName(name);
    setIsNew(false);
    navigate(`/schema/${name}`);
  };

  const handleNew = () => {
    setSelectedName(null);
    setIsNew(true);
    navigate('/schema');
  };

  const handleSave = (type: ResourceType) => {
    saveMutation.mutate({ type, isNew });
    setIsNew(false);
    setSelectedName(type.name);
    navigate(`/schema/${type.name}`);
  };

  const newTypeTemplate: ResourceType = {
    name: '',
    fields: [],
    abilities: ['key', 'query'],
    stateMachine: [],
    relationships: [],
    version: 1,
  };

  return (
    <Box style={{ display: 'flex', height: '100%' }}>
      <Box w={260} style={{ borderRight: '1px solid var(--mantine-color-default-border)', overflow: 'auto', flexShrink: 0 }}>
        <TypeList
          types={types}
          selected={selectedName}
          onSelect={handleSelect}
          onNew={handleNew}
        />
      </Box>
      <Box style={{ flex: 1, overflow: 'auto' }}>
        <TypeEditor
          key={isNew ? '__new__' : (selectedName ?? '__none__')}
          type={isNew ? newTypeTemplate : selectedType}
          allTypes={types}
          onSave={handleSave}
          isNew={isNew}
        />
      </Box>
    </Box>
  );
}
