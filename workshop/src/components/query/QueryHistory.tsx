import { useState } from 'react';
import { Stack, TextInput, ScrollArea, Card, Group, Text, ActionIcon, Code, Box, Divider } from '@mantine/core';
import { IconSearch, IconStar, IconStarFilled, IconTrash } from '@tabler/icons-react';
import type { SavedQuery } from '@/lib/types';

interface QueryHistoryProps {
  queries: SavedQuery[];
  onSelect: (query: SavedQuery) => void;
  onDelete: (id: string) => void;
  onToggleFavorite: (id: string) => void;
}

export function QueryHistory({ queries, onSelect, onDelete, onToggleFavorite }: QueryHistoryProps) {
  const [search, setSearch] = useState('');
  const filtered = queries.filter((q) =>
    q.name.toLowerCase().includes(search.toLowerCase()),
  );

  return (
    <Stack gap={0} h="100%">
      <Stack p="sm" gap="xs">
        <TextInput
          placeholder="Search queries..."
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
            No saved queries
          </Text>
        )}
        {filtered.map((q) => (
          <Card
            key={q.id}
            p="sm"
            withBorder={false}
            style={{ borderBottom: '1px solid var(--mantine-color-default-border)', borderRadius: 0, cursor: 'pointer' }}
            onClick={() => onSelect(q)}
          >
            <Group justify="space-between" wrap="nowrap" align="flex-start">
              <Box style={{ minWidth: 0, flex: 1 }}>
                <Text size="sm" fw={500} truncate>{q.name}</Text>
                <Code block fz="xs" mt={4} style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {JSON.stringify(q.query).slice(0, 80)}
                </Code>
              </Box>
              <Group gap={2} wrap="nowrap">
                <ActionIcon
                  onClick={(e) => { e.stopPropagation(); onToggleFavorite(q.id); }}
                  color="yellow"
                  variant="subtle"
                  aria-label={q.favorited ? 'Unfavorite' : 'Favorite'}
                  size="sm"
                >
                  {q.favorited ? <IconStarFilled size={14} /> : <IconStar size={14} />}
                </ActionIcon>
                <ActionIcon
                  onClick={(e) => { e.stopPropagation(); onDelete(q.id); }}
                  color="red"
                  variant="subtle"
                  aria-label="Delete query"
                  size="sm"
                >
                  <IconTrash size={14} />
                </ActionIcon>
              </Group>
            </Group>
          </Card>
        ))}
      </ScrollArea>
    </Stack>
  );
}
