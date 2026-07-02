import { useState } from 'react';
import { NavLink } from 'react-router-dom';
import { Paper, Group, Title, Table, Collapse, ActionIcon, Box, Text, UnstyledButton } from '@mantine/core';
import { IconChevronDown, IconChevronUp } from '@tabler/icons-react';
import type { ResourceType } from '@/lib/types';

interface ResourceTypePanelProps {
  types: ResourceType[];
}

export function ResourceTypePanel({ types }: ResourceTypePanelProps) {
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
        <Title order={3} size="h4">Resource Types ({types.length})</Title>
        <ActionIcon variant="subtle" size="sm" aria-label={open ? 'Collapse' : 'Expand'}>
          {open ? <IconChevronUp size={16} /> : <IconChevronDown size={16} />}
        </ActionIcon>
      </Group>
      <Collapse expanded={open}>
        {types.length === 0 ? (
          <Box px="md" pb="md">
            <Text size="sm" c="dimmed">No types defined</Text>
          </Box>
        ) : (
          <Table verticalSpacing="xs" horizontalSpacing="sm">
            <Table.Tbody>
              {types.map((type) => (
                <Table.Tr key={type.name}>
                  <Table.Td>
                    <UnstyledButton
                      component={NavLink}
                      to={`/schema/${type.name}`}
                      style={{ display: 'block', width: '100%' }}
                    >
                      <Group justify="space-between" px="xs" py={4} style={{ borderRadius: 4 }}>
                        <Text size="sm">{type.name}</Text>
                        <Text size="xs" c="dimmed">v{type.version}</Text>
                      </Group>
                    </UnstyledButton>
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
