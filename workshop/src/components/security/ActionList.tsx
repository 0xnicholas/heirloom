import { useState } from 'react';
import { Stack, TextInput, ScrollArea, UnstyledButton, Group, Text, Button, Divider } from '@mantine/core';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import type { Action } from '@/lib/types';

interface ActionListProps {
  actions: Action[];
  selected: string | null;
  onSelect: (name: string) => void;
  onNew: () => void;
}

export function ActionList({ actions, selected, onSelect, onNew }: ActionListProps) {
  const [search, setSearch] = useState('');
  const filtered = actions.filter((a) =>
    a.name.toLowerCase().includes(search.toLowerCase()),
  );

  return (
    <Stack gap={0} h="100%">
      <Stack p="sm" gap="xs">
        <TextInput
          placeholder="Search actions..."
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
            No actions found
          </Text>
        )}
        {filtered.map((action) => (
          <UnstyledButton
            key={action.name}
            onClick={() => onSelect(action.name)}
            data-selected={selected === action.name || undefined}
            style={{
              width: '100%',
              padding: '8px 16px',
              fontSize: 14,
              backgroundColor: selected === action.name ? 'var(--mantine-color-indigo-0)' : undefined,
              color: selected === action.name ? 'var(--mantine-color-indigo-7)' : undefined,
              fontWeight: selected === action.name ? 500 : 400,
            }}
          >
            <Text size="sm">{action.name}</Text>
            <Group gap={4} mt={2}>
              <Text size="xs" c="dimmed">{action.targetType}</Text>
              <Text size="xs" c="dimmed">·</Text>
              <Text size="xs" c="dimmed">requires {action.requires}</Text>
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
          New Action
        </Button>
      </Stack>
    </Stack>
  );
}
