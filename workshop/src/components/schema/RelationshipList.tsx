import { ActionIcon, Button, Group, Select, Table, Text, TextInput, Stack } from '@mantine/core';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import type { Relationship, RelationshipSemantics } from '@/lib/types';
import { RELATIONSHIP_SEMANTICS } from '@/lib/constants';

interface RelationshipListProps {
  relationships: Relationship[];
  typeName: string;
  allTypes: string[];
  onChange: (relationships: Relationship[]) => void;
}

export function RelationshipList({ relationships, typeName, allTypes, onChange }: RelationshipListProps) {
  const updateRel = (index: number, patch: Partial<Relationship>) => {
    const updated = relationships.map((r, i) => (i === index ? { ...r, ...patch } : r));
    onChange(updated);
  };

  const removeRel = (index: number) => {
    onChange(relationships.filter((_, i) => i !== index));
  };

  const addRel = () => {
    onChange([
      ...relationships,
      { label: '', targetType: allTypes[0] || '', semantics: 'Association' },
    ]);
  };

  return (
    <Stack gap="xs">
      <Text size="sm" fw={600}>Relationships</Text>
      {relationships.length === 0 && (
        <Text size="sm" c="dimmed">No relationships defined</Text>
      )}
      {relationships.length > 0 && (
        <Table withTableBorder withColumnBorders verticalSpacing="xs" horizontalSpacing="sm">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>From</Table.Th>
              <Table.Th>Label</Table.Th>
              <Table.Th>To</Table.Th>
              <Table.Th>Semantics</Table.Th>
              <Table.Th w={40}></Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {relationships.map((rel, i) => (
              <Table.Tr key={i}>
                <Table.Td>
                  <Text size="xs" ff="monospace" c="dimmed">{typeName}</Text>
                </Table.Td>
                <Table.Td>
                  <TextInput
                    size="xs"
                    value={rel.label}
                    onChange={(e) => updateRel(i, { label: e.currentTarget.value })}
                    placeholder="label"
                    aria-label="Relationship label"
                  />
                </Table.Td>
                <Table.Td>
                  <Select
                    size="xs"
                    value={rel.targetType}
                    onChange={(value) => value && updateRel(i, { targetType: value })}
                    data={allTypes.map((t) => ({ value: t, label: t }))}
                    allowDeselect={false}
                    comboboxProps={{ withinPortal: true }}
                  />
                </Table.Td>
                <Table.Td>
                  <Select
                    size="xs"
                    value={rel.semantics}
                    onChange={(value) => value && updateRel(i, { semantics: value as RelationshipSemantics })}
                    data={RELATIONSHIP_SEMANTICS.map((s) => ({ value: s, label: s }))}
                    allowDeselect={false}
                    comboboxProps={{ withinPortal: true }}
                  />
                </Table.Td>
                <Table.Td>
                  <ActionIcon
                    onClick={() => removeRel(i)}
                    color="red"
                    variant="subtle"
                    aria-label="Remove relationship"
                    size="sm"
                  >
                    <IconTrash size={14} />
                  </ActionIcon>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      <Group>
        <Button
          onClick={addRel}
          variant="subtle"
          size="xs"
          leftSection={<IconPlus size={14} />}
        >
          Add Relationship
        </Button>
      </Group>
    </Stack>
  );
}
