import { useState } from 'react';
import { Paper, Group, Title, Badge, Table, Collapse, ActionIcon, Box, Text } from '@mantine/core';
import { IconChevronDown, IconChevronUp } from '@tabler/icons-react';
import type { Relationship, RelationshipSemantics } from '@/lib/types';

interface RelationshipPanelProps {
  relationships: (Relationship & { source: string })[];
}

const SEMANTICS_COLORS: Record<RelationshipSemantics, string> = {
  Ownership: 'red',
  Reference: 'blue',
  Association: 'gray',
};

export function RelationshipPanel({ relationships }: RelationshipPanelProps) {
  const [open, setOpen] = useState(true);

  return (
    <Paper withBorder shadow="sm" radius="md">
      <Group
        justify="space-between"
        px="md"
        py="sm"
        style={{ cursor: 'pointer' }}
        onClick={() => setOpen((o) => !o)}
      >
        <Title order={3} size="h4">Relationships ({relationships.length})</Title>
        <ActionIcon variant="subtle" size="sm" aria-label={open ? 'Collapse' : 'Expand'}>
          {open ? <IconChevronUp size={16} /> : <IconChevronDown size={16} />}
        </ActionIcon>
      </Group>
      <Collapse expanded={open}>
        {relationships.length === 0 ? (
          <Box px="md" pb="md">
            <Text size="sm" c="dimmed">No relationships defined</Text>
          </Box>
        ) : (
          <Table verticalSpacing="xs" horizontalSpacing="sm">
            <Table.Tbody>
              {relationships.map((rel, i) => (
                <Table.Tr key={i}>
                  <Table.Td>
                    <Text size="sm" fw={500}>{rel.source}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" c="dimmed" ta="center">→</Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" fw={500}>{rel.targetType}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Badge size="xs" color={SEMANTICS_COLORS[rel.semantics]} variant="light">
                      {rel.semantics}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" c="dimmed" ff="monospace">{rel.label}</Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Collapse>
    </Paper>
  );
}
