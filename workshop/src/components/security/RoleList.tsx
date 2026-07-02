import { useState } from 'react';
import { Stack, TextInput, ScrollArea, UnstyledButton, Group, Text, Button, Divider } from '@mantine/core';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import type { Role } from '@/lib/types';

interface RoleListProps {
  roles: Role[];
  selected: string | null;
  onSelect: (name: string) => void;
  onNew: () => void;
}

export function RoleList({ roles, selected, onSelect, onNew }: RoleListProps) {
  const [search, setSearch] = useState('');
  const filtered = roles.filter((r) =>
    r.name.toLowerCase().includes(search.toLowerCase()),
  );

  return (
    <Stack gap={0} h="100%">
      <Stack p="sm" gap="xs">
        <TextInput
          placeholder="Search roles..."
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
            No roles found
          </Text>
        )}
        {filtered.map((role) => (
          <UnstyledButton
            key={role.name}
            onClick={() => onSelect(role.name)}
            data-selected={selected === role.name || undefined}
            style={{
              width: '100%',
              padding: '8px 16px',
              fontSize: 14,
              backgroundColor: selected === role.name ? 'var(--mantine-color-indigo-0)' : undefined,
              color: selected === role.name ? 'var(--mantine-color-indigo-7)' : undefined,
              fontWeight: selected === role.name ? 500 : 400,
            }}
          >
            <Text size="sm">{role.name}</Text>
            <Group gap={4} mt={2}>
              <Text size="xs" c="dimmed">
                {role.actors.length} actor{role.actors.length !== 1 ? 's' : ''}
              </Text>
              <Text size="xs" c="dimmed">·</Text>
              <Text size="xs" c="dimmed">
                {role.capabilities.length} capabilit{role.capabilities.length !== 1 ? 'ies' : 'y'}
              </Text>
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
          New Role
        </Button>
      </Stack>
    </Stack>
  );
}
