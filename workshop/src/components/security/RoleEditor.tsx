import { useEffect } from 'react';
import { useForm } from '@mantine/form';
import {
  ActionIcon, Box, Button, Center, Group, MultiSelect, Paper, ScrollArea, Select, Stack, Table, Text, TextInput,
} from '@mantine/core';
import { IconDeviceFloppy, IconPlus, IconTrash } from '@tabler/icons-react';
import type { Role, RoleScope, Ability, ResourceType } from '@/lib/types';
import { ABILITIES } from '@/lib/constants';

interface RoleEditorProps {
  role: Role | null;
  allTypes: ResourceType[];
  onSave: (role: Role) => void;
  isNew?: boolean;
}

export function RoleEditor({ role, allTypes, onSave }: RoleEditorProps) {
  const form = useForm<Role>({
    mode: 'controlled',
    initialValues: role ?? {
      name: '',
      scope: 'Ontology',
      targets: [],
      capabilities: [],
      actors: [],
    },
    validate: {
      name: (value) => (value.trim() ? null : 'Name is required'),
    },
  });

  useEffect(() => {
    if (role) {
      form.setValues(role);
      form.resetDirty();
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [role]);

  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (form.isDirty()) {
        e.preventDefault();
        e.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [form]);

  if (!role) {
    return (
      <Center h="100%">
        <Text c="dimmed">Select a role or create one</Text>
      </Center>
    );
  }

  const handleSave = () => {
    if (form.validate().hasErrors) return;
    onSave(form.values);
    form.resetDirty();
  };

  const addCapability = () => {
    form.setFieldValue('capabilities', [
      ...form.values.capabilities,
      { ability: 'query', targetType: allTypes[0]?.name || '*', scope: form.values.scope },
    ]);
  };

  const removeCapability = (i: number) => {
    form.setFieldValue('capabilities', form.values.capabilities.filter((_, idx) => idx !== i));
  };

  const updateCapability = (
    i: number,
    patch: Partial<{ ability: Ability; targetType: string; scope: RoleScope }>,
  ) => {
    form.setFieldValue(
      'capabilities',
      form.values.capabilities.map((c, idx) => (idx === i ? { ...c, ...patch } : c)),
    );
  };

  const addActor = () => {
    form.setFieldValue('actors', [...form.values.actors, '']);
  };

  const updateActor = (i: number, value: string) => {
    form.setFieldValue('actors', form.values.actors.map((a, idx) => (idx === i ? value : a)));
  };

  const removeActor = (i: number) => {
    form.setFieldValue('actors', form.values.actors.filter((_, idx) => idx !== i));
  };

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
            placeholder="Role name"
            styles={{ input: { fontWeight: 600, fontSize: 18 } }}
            style={{ flex: 1 }}
          />
          <Button
            onClick={handleSave}
            disabled={!isDirty}
            leftSection={<IconDeviceFloppy size={16} />}
          >
            {isDirty ? 'Save *' : 'Save'}
          </Button>
        </Group>
      </Paper>

      <ScrollArea style={{ flex: 1 }}>
        <Box p="md">
          <Stack gap="lg">
            {/* Scope + Targets */}
            <Group align="flex-start" gap="xl">
              <Box>
                <Text size="sm" fw={600} mb="xs">Scope</Text>
                <Select
                  size="sm"
                  value={form.values.scope}
                  onChange={(value) => value && form.setFieldValue('scope', value as RoleScope)}
                  data={[
                    { value: 'Ontology', label: 'Ontology' },
                    { value: 'Type', label: 'Type' },
                    { value: 'Instance', label: 'Instance' },
                  ]}
                  allowDeselect={false}
                  comboboxProps={{ withinPortal: true }}
                />
              </Box>

              {form.values.scope !== 'Ontology' && (
                <Box style={{ flex: 1 }}>
                  <Text size="sm" fw={600} mb="xs">Targets</Text>
                  <MultiSelect
                    size="sm"
                    placeholder="Pick target types"
                    data={allTypes.map((t) => ({ value: t.name, label: t.name }))}
                    value={form.values.targets}
                    onChange={(values) => form.setFieldValue('targets', values)}
                    comboboxProps={{ withinPortal: true }}
                    clearable
                  />
                </Box>
              )}
            </Group>

            {/* Granted Capabilities */}
            <Box>
              <Text size="sm" fw={600} mb="xs">Granted Capabilities</Text>
              {form.values.capabilities.length > 0 && (
                <Table withTableBorder verticalSpacing="xs" horizontalSpacing="sm">
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Ability</Table.Th>
                      <Table.Th>Target Type</Table.Th>
                      <Table.Th>Scope</Table.Th>
                      <Table.Th w={40}></Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {form.values.capabilities.map((cap, i) => (
                      <Table.Tr key={i}>
                        <Table.Td>
                          <Select
                            size="xs"
                            value={cap.ability}
                            onChange={(value) => value && updateCapability(i, { ability: value as Ability })}
                            data={ABILITIES.map((a) => ({ value: a, label: a }))}
                            allowDeselect={false}
                            comboboxProps={{ withinPortal: true }}
                          />
                        </Table.Td>
                        <Table.Td>
                          <Select
                            size="xs"
                            value={cap.targetType}
                            onChange={(value) => value && updateCapability(i, { targetType: value })}
                            data={[{ value: '*', label: '* (Global)' }, ...allTypes.map((t) => ({ value: t.name, label: t.name }))]}
                            allowDeselect={false}
                            comboboxProps={{ withinPortal: true }}
                          />
                        </Table.Td>
                        <Table.Td>
                          <Select
                            size="xs"
                            value={cap.scope}
                            onChange={(value) => value && updateCapability(i, { scope: value as RoleScope })}
                            data={[
                              { value: 'Ontology', label: 'Ontology' },
                              { value: 'Type', label: 'Type' },
                              { value: 'Instance', label: 'Instance' },
                            ]}
                            allowDeselect={false}
                            comboboxProps={{ withinPortal: true }}
                          />
                        </Table.Td>
                        <Table.Td>
                          <ActionIcon
                            onClick={() => removeCapability(i)}
                            color="red"
                            variant="subtle"
                            size="sm"
                            aria-label="Remove capability"
                          >
                            <IconTrash size={14} />
                          </ActionIcon>
                        </Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              )}
              {form.values.capabilities.length === 0 && (
                <Text size="xs" c="dimmed" my="xs">No capabilities granted</Text>
              )}
              <Group mt="xs">
                <Button
                  onClick={addCapability}
                  variant="subtle"
                  size="xs"
                  leftSection={<IconPlus size={14} />}
                >
                  Grant Capability
                </Button>
              </Group>
            </Box>

            {/* Assigned Actors */}
            <Box>
              <Text size="sm" fw={600} mb="xs">Assigned Actors</Text>
              {form.values.actors.length === 0 && (
                <Text size="xs" c="dimmed" my="xs">No actors assigned</Text>
              )}
              <Stack gap="xs">
                {form.values.actors.map((actor, i) => (
                  <Group key={i} gap="xs">
                    <TextInput
                      size="sm"
                      style={{ flex: 1 }}
                      value={actor}
                      onChange={(e) => updateActor(i, e.currentTarget.value)}
                      placeholder="agent.name or user.name"
                    />
                    <ActionIcon
                      onClick={() => removeActor(i)}
                      color="red"
                      variant="subtle"
                      aria-label="Remove actor"
                    >
                      <IconTrash size={14} />
                    </ActionIcon>
                  </Group>
                ))}
              </Stack>
              <Group mt="xs">
                <Button
                  onClick={addActor}
                  variant="subtle"
                  size="xs"
                  leftSection={<IconPlus size={14} />}
                >
                  Assign Actor
                </Button>
              </Group>
            </Box>
          </Stack>
        </Box>
      </ScrollArea>
    </Stack>
  );
}
