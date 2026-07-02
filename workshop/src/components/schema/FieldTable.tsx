import { useState, useCallback } from 'react';
import { ActionIcon, Button, Checkbox, Group, Select, Table, Text, TextInput, Stack } from '@mantine/core';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import type { Field, FieldType } from '@/lib/types';
import { FIELD_TYPES } from '@/lib/constants';

interface FieldTableProps {
  fields: Field[];
  onChange: (fields: Field[]) => void;
}

export function FieldTable({ fields, onChange }: FieldTableProps) {
  const [dragIndex, setDragIndex] = useState<number | null>(null);

  const updateField = (index: number, patch: Partial<Field>) => {
    const updated = fields.map((f, i) => (i === index ? { ...f, ...patch } : f));
    onChange(updated);
  };

  const removeField = (index: number) => {
    onChange(fields.filter((_, i) => i !== index));
  };

  const addField = () => {
    onChange([...fields, { name: '', type: 'string', required: false }]);
  };

  const handleDragStart = (index: number) => setDragIndex(index);

  const handleDragOver = useCallback((e: React.DragEvent, index: number) => {
    e.preventDefault();
    if (dragIndex === null || dragIndex === index) return;
    const reordered = [...fields];
    const [moved] = reordered.splice(dragIndex, 1);
    reordered.splice(index, 0, moved);
    onChange(reordered);
    setDragIndex(index);
  }, [dragIndex, fields, onChange]);

  const handleDragEnd = () => setDragIndex(null);

  return (
    <Stack gap="xs">
      <Text size="sm" fw={600}>Fields</Text>
      {fields.length > 0 && (
        <Table withTableBorder verticalSpacing="xs" horizontalSpacing="sm">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Name</Table.Th>
              <Table.Th>Type</Table.Th>
              <Table.Th ta="center" w={100}>Required</Table.Th>
              <Table.Th w={40}></Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {fields.map((field, i) => (
              <Table.Tr
                key={i}
                draggable
                onDragStart={() => handleDragStart(i)}
                onDragOver={(e) => handleDragOver(e, i)}
                onDragEnd={handleDragEnd}
                style={{ cursor: 'grab', opacity: dragIndex === i ? 0.5 : 1 }}
              >
                <Table.Td>
                  <TextInput
                    size="xs"
                    value={field.name}
                    onChange={(e) => updateField(i, { name: e.currentTarget.value })}
                    placeholder="field_name"
                    aria-label="Field name"
                  />
                </Table.Td>
                <Table.Td>
                  <Select
                    size="xs"
                    value={field.type}
                    onChange={(value) => value && updateField(i, { type: value as FieldType })}
                    data={FIELD_TYPES.map((ft) => ({ value: ft, label: ft }))}
                    allowDeselect={false}
                    comboboxProps={{ withinPortal: true }}
                  />
                </Table.Td>
                <Table.Td>
                  <Group justify="center">
                    <Checkbox
                      checked={field.required}
                      onChange={(e) => updateField(i, { required: e.currentTarget.checked })}
                      aria-label={`${field.name || 'field'} required`}
                    />
                  </Group>
                </Table.Td>
                <Table.Td>
                  <ActionIcon
                    onClick={() => removeField(i)}
                    color="red"
                    variant="subtle"
                    aria-label={`Remove ${field.name || 'field'}`}
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
          onClick={addField}
          variant="subtle"
          size="xs"
          leftSection={<IconPlus size={14} />}
        >
          Add Field
        </Button>
      </Group>
    </Stack>
  );
}
