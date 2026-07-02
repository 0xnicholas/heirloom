import { useEffect, useState, useRef } from 'react';
import { useForm } from '@mantine/form';
import {
  ActionIcon, Box, Button, Center, Checkbox, Group, Paper, ScrollArea, Select, SimpleGrid, Stack, Table, Text, Textarea, TextInput,
} from '@mantine/core';
import { IconDeviceFloppy, IconPlus, IconTrash } from '@tabler/icons-react';
import type { Action, Ability, ResourceType, FieldType, Diagnostic } from '@/lib/types';
import { FIELD_TYPES } from '@/lib/constants';
import { ValidationBar } from '@/components/shared/ValidationBar';
import { createSnapshot } from '@/lib/validation/registry-snapshot';
import { validateAction } from '@/lib/validation/action-validator';

interface ActionEditorProps {
  action: Action | null;
  allTypes: ResourceType[];
  onSave: (action: Action) => void;
  isNew?: boolean;
}

export function ActionEditor({ action, allTypes, onSave }: ActionEditorProps) {
  const form = useForm<Action>({
    mode: 'controlled',
    initialValues: action ?? {
      name: '',
      targetType: allTypes[0]?.name || '',
      requires: 'query',
      parameters: [],
      validateRules: [],
      executeTemplate: '',
    },
    validate: {
      name: (value) => (value.trim() ? null : 'Name is required'),
    },
  });

  const [diagnostics, setDiagnostics] = useState<Diagnostic[]>([]);
  const timerRef = useRef<ReturnType<typeof setTimeout>>(undefined);

  useEffect(() => {
    if (action) {
      form.setValues(action);
      form.resetDirty();
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [action]);

  // Debounced live validation (300ms)
  useEffect(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      if (!action) {
        setDiagnostics([]);
        return;
      }
      const snapshot = createSnapshot(allTypes, [], []);
      setDiagnostics(validateAction(action, snapshot));
    }, 300);
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [action, allTypes]);

  if (!action) {
    return (
      <Center h="100%">
        <Text c="dimmed">Select an action or create one</Text>
      </Center>
    );
  }

  // Derive available options based on currently selected target type
  const targetType = allTypes.find((t) => t.name === action.targetType);
  const declaredAbilities = targetType?.abilities || [];
  const stateMachineStates = new Set(
    targetType?.stateMachine.flatMap((t) => [t.from, t.to]) || [],
  );

  const handleSave = () => {
    if (form.validate().hasErrors) return;
    onSave(form.values);
    form.resetDirty();
  };

  const addParam = () => {
    form.setFieldValue('parameters', [
      ...form.values.parameters,
      { name: '', type: 'string', required: false },
    ]);
  };

  const updateParam = (
    i: number,
    patch: Partial<{ name: string; type: FieldType; required: boolean }>,
  ) => {
    form.setFieldValue(
      'parameters',
      form.values.parameters.map((p, idx) => (idx === i ? { ...p, ...patch } : p)),
    );
  };

  const removeParam = (i: number) => {
    form.setFieldValue('parameters', form.values.parameters.filter((_, idx) => idx !== i));
  };

  const hasErrors = diagnostics.some((d) => d.severity === 'error');
  const isDirty = form.isDirty();

  return (
    <Stack h="100%" gap={0}>
      {/* Header */}
      <Paper
        p="md"
        radius={0}
        style={{ borderBottom: '1px solid var(--mantine-color-default-border)', position: 'sticky', top: 0, zIndex: 10, backgroundColor: 'var(--mantine-color-body)' }}
      >
        <Group justify="space-between" align="center">
          <TextInput
            size="md"
            variant="unstyled"
            {...form.getInputProps('name')}
            placeholder="Action name"
            styles={{ input: { fontWeight: 600, fontSize: 18 } }}
            style={{ flex: 1 }}
          />
          <Group gap="sm">
            <ValidationBar diagnostics={diagnostics} />
            <Button
              onClick={handleSave}
              disabled={hasErrors}
              leftSection={<IconDeviceFloppy size={16} />}
            >
              {isDirty ? 'Save *' : 'Save'}
            </Button>
          </Group>
        </Group>
      </Paper>

      <ScrollArea style={{ flex: 1 }}>
        <Box p="md">
          <Stack gap="lg">
            {/* Target Type / Requires / Gate */}
            <SimpleGrid cols={3} spacing="md">
              <Box>
                <Text size="sm" fw={600} mb="xs">Target Type</Text>
                <Select
                  size="sm"
                  value={form.values.targetType}
                  onChange={(value) => {
                    form.setFieldValue('targetType', value || '');
                    form.setFieldValue('requires', 'query');
                  }}
                  data={allTypes.map((t) => ({ value: t.name, label: t.name }))}
                  allowDeselect={false}
                  comboboxProps={{ withinPortal: true }}
                />
              </Box>

              <Box>
                <Text size="sm" fw={600} mb="xs">Requires</Text>
                <Select
                  size="sm"
                  value={form.values.requires}
                  onChange={(value) => value && form.setFieldValue('requires', value as Ability)}
                  data={
                    declaredAbilities.length > 0
                      ? declaredAbilities.map((a) => ({ value: a, label: a }))
                      : [{ value: form.values.requires, label: `${form.values.requires} (not on target type)` }]
                  }
                  allowDeselect={false}
                  comboboxProps={{ withinPortal: true }}
                />
              </Box>

              <Box>
                <Text size="sm" fw={600} mb="xs">Gate (state)</Text>
                <TextInput
                  size="sm"
                  value={form.values.gate?.state || ''}
                  onChange={(e) =>
                    form.setFieldValue('gate', e.currentTarget.value ? { state: e.currentTarget.value } : undefined)
                  }
                  placeholder="e.g. Active"
                  list="gate-states-list"
                />
                <datalist id="gate-states-list">
                  {[...stateMachineStates].map((s) => (
                    <option key={s} value={s} />
                  ))}
                </datalist>
              </Box>
            </SimpleGrid>

            {/* Parameters */}
            <Box>
              <Text size="sm" fw={600} mb="xs">Parameters</Text>
              {form.values.parameters.length > 0 && (
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
                    {form.values.parameters.map((p, i) => (
                      <Table.Tr key={i}>
                        <Table.Td>
                          <TextInput
                            size="xs"
                            value={p.name}
                            onChange={(e) => updateParam(i, { name: e.currentTarget.value })}
                            placeholder="param_name"
                            aria-label="Parameter name"
                          />
                        </Table.Td>
                        <Table.Td>
                          <Select
                            size="xs"
                            value={p.type}
                            onChange={(value) => value && updateParam(i, { type: value as FieldType })}
                            data={FIELD_TYPES.map((ft) => ({ value: ft, label: ft }))}
                            allowDeselect={false}
                            comboboxProps={{ withinPortal: true }}
                          />
                        </Table.Td>
                        <Table.Td>
                          <Group justify="center">
                            <Checkbox
                              checked={p.required}
                              onChange={(e) => updateParam(i, { required: e.currentTarget.checked })}
                              aria-label={`${p.name} required`}
                            />
                          </Group>
                        </Table.Td>
                        <Table.Td>
                          <ActionIcon
                            onClick={() => removeParam(i)}
                            color="red"
                            variant="subtle"
                            size="sm"
                            aria-label={`Remove parameter ${p.name}`}
                          >
                            <IconTrash size={14} />
                          </ActionIcon>
                        </Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              )}
              {form.values.parameters.length === 0 && (
                <Text size="xs" c="dimmed" my="xs">No parameters defined</Text>
              )}
              <Group mt="xs">
                <Button
                  onClick={addParam}
                  variant="subtle"
                  size="xs"
                  leftSection={<IconPlus size={14} />}
                >
                  Add Parameter
                </Button>
              </Group>
            </Box>

            {/* Validate Rules */}
            <Box>
              <Text size="sm" fw={600} mb="xs">Validate Rules</Text>
              <Textarea
                size="sm"
                rows={3}
                value={form.values.validateRules.join('\n')}
                onChange={(e) =>
                  form.setFieldValue(
                    'validateRules',
                    e.currentTarget.value.split('\n').filter((line) => line.trim() !== ''),
                  )
                }
                styles={{ input: { fontFamily: 'monospace' } }}
                placeholder="risk_score(inventory) > 0.3"
              />
            </Box>

            {/* Execute */}
            <Box>
              <Text size="sm" fw={600} mb="xs">Execute</Text>
              <Textarea
                size="sm"
                rows={3}
                value={form.values.executeTemplate}
                onChange={(e) => form.setFieldValue('executeTemplate', e.currentTarget.value)}
                styles={{ input: { fontFamily: 'monospace' } }}
                placeholder="(Action execution DSL — format TBD in Phase 2 implementation)"
              />
            </Box>
          </Stack>
        </Box>
      </ScrollArea>
    </Stack>
  );
}
