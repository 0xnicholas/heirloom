import { useState } from 'react';
import { Stack, TextInput, ScrollArea, UnstyledButton, Group, Text, Button, Divider } from '@mantine/core';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import type { ResourceType } from '@/lib/types';

interface TypeListProps {
  types: ResourceType[];
  selected: string | null;
  onSelect: (name: string) => void;
  onNew: () => void;
}

export function TypeList({ types, selected, onSelect, onNew }: TypeListProps) {
  const [search, setSearch] = useState('');
  const filtered = types.filter((t) =>
    t.name.toLowerCase().includes(search.toLowerCase()),
  );

  return (
    <Stack gap={0} h="100%">
      <Stack p="sm" gap="xs">
        <TextInput
          placeholder="Search types..."
          value={search}
          onChange={(e) => setSearch(e.currentTarget.value)}
          leftSection={<IconSearch size={14} />}
          size="sm"
        />
      </Stack>
      <Divider />
      <ScrollArea style={{ flex: 1 }}>
        {filtered.length === 0 && (
          <Text size="sm" c="dimmed" px="md" py="sm">
            No types found
          </Text>
        )}
        {filtered.map((type) => (
          <UnstyledButton
            key={type.name}
            onClick={() => onSelect(type.name)}
            data-selected={selected === type.name || undefined}
            style={{
              width: '100%',
              padding: '8px 16px',
              fontSize: 14,
              backgroundColor: selected === type.name ? 'var(--mantine-color-indigo-0)' : undefined,
              color: selected === type.name ? 'var(--mantine-color-indigo-7)' : undefined,
              fontWeight: selected === type.name ? 500 : 400,
            }}
          >
            <Group gap="xs" justify="space-between">
              <span>{type.name}</span>
              <Text size="xs" c="dimmed">v{type.version}</Text>
            </Group>
          </UnstyledButton>
        ))}
      </ScrollArea>
      <Divider />
      <Stack p="sm">
        <Button
          onClick={onNew}
          variant="light"
          size="sm"
          leftSection={<IconPlus size={14} />}
          fullWidth
        >
          New Type
        </Button>
      </Stack>
    </Stack>
  );
}
