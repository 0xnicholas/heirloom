import { useEffect } from 'react';
import { useForm } from '@mantine/form';
import {
  Box, Button, Group, Paper, ScrollArea, Stack, Tabs, Text, TextInput, Center,
} from '@mantine/core';
import { IconDeviceFloppy } from '@tabler/icons-react';
import type { ResourceType, Ability, SchemaRegistrySnapshot, Diagnostic } from '@/lib/types';
import { FieldTable } from './FieldTable';
import { StateMachineEditor } from './StateMachineEditor';
import { AbilitiesMatrix } from './AbilitiesMatrix';
import { RelationshipList } from './RelationshipList';
import { ValidationBar } from '@/components/shared/ValidationBar';
import { createSnapshot } from '@/lib/validation/registry-snapshot';
import { validateType } from '@/lib/validation/type-validator';

function useRegistrySnapshot(types: ResourceType[]) {
  return createSnapshot(types, [], []);
}

function useDebouncedTypeValidation(type: ResourceType | null, snapshot: SchemaRegistrySnapshot): Diagnostic[] {
  if (!type) return [];
  return validateType(type, snapshot);
}

interface TypeEditorProps {
  type: ResourceType | null;
  allTypes: ResourceType[];
  onSave: (type: ResourceType) => void;
  isNew?: boolean;
}

export function TypeEditor({ type, allTypes, onSave }: TypeEditorProps) {
  const form = useForm<ResourceType>({
    mode: 'controlled',
    initialValues: type ?? {
      name: '',
      description: '',
      fields: [],
      abilities: [],
      stateMachine: [],
      relationships: [],
      version: 1,
    },
    validate: {
      name: (value) => (value.trim() ? null : 'Name is required'),
    },
  });

  // Reset form when edited entity changes
  useEffect(() => {
    if (type) {
      form.setValues(type);
      form.resetDirty();
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [type]);

  // Warn on browser tab close with unsaved changes
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

  const snapshot = useRegistrySnapshot(allTypes);
  const diagnostics = useDebouncedTypeValidation(form.values, snapshot);

  if (!type) {
    return (
      <Center h="100%">
        <Text c="dimmed">Select a type or create a new one</Text>
      </Center>
    );
  }

  const handleSave = () => {
    if (form.validate().hasErrors) return;
    onSave(form.values);
    form.resetDirty();
  };

  const hasErrors = diagnostics.some((d) => d.severity === 'error');
  const isDirty = form.isDirty();

  return (
    <Stack h="100%" gap={0}>
      {/* Header */}
      <Paper
        p="md"
        withBorder={false}
        radius={0}
        style={{ borderBottom: '1px solid var(--mantine-color-default-border)', position: 'sticky', top: 0, zIndex: 10, backgroundColor: 'var(--mantine-color-body)' }}
      >
        <Group justify="space-between" align="center">
          <TextInput
            size="md"
            variant="unstyled"
            {...form.getInputProps('name')}
            placeholder="Type name"
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

      {/* Body */}
      <ScrollArea style={{ flex: 1 }}>
        <Box p="md">
          <Stack gap="lg">
            {/* Description */}
            <Box>
              <Text size="sm" fw={600} mb="xs">Description</Text>
              <TextInput
                size="sm"
                placeholder="Optional description"
                {...form.getInputProps('description')}
              />
            </Box>

            <Tabs defaultValue="fields" keepMounted={false}>
              <Tabs.List>
                <Tabs.Tab value="fields">Fields</Tabs.Tab>
                <Tabs.Tab value="abilities">Abilities</Tabs.Tab>
                <Tabs.Tab value="states">State Machine</Tabs.Tab>
                <Tabs.Tab value="relationships">Relationships</Tabs.Tab>
              </Tabs.List>

              <Tabs.Panel value="fields" pt="md">
                <FieldTable
                  fields={form.values.fields}
                  onChange={(fields) => form.setFieldValue('fields', fields)}
                />
              </Tabs.Panel>

              <Tabs.Panel value="abilities" pt="md">
                <AbilitiesMatrix
                  selected={form.values.abilities}
                  onChange={(abilities) => form.setFieldValue('abilities', abilities as Ability[])}
                />
              </Tabs.Panel>

              <Tabs.Panel value="states" pt="md">
                <StateMachineEditor
                  key={form.values.name}
                  transitions={form.values.stateMachine}
                  onChange={(stateMachine) => form.setFieldValue('stateMachine', stateMachine)}
                />
              </Tabs.Panel>

              <Tabs.Panel value="relationships" pt="md">
                <RelationshipList
                  relationships={form.values.relationships}
                  typeName={form.values.name}
                  allTypes={allTypes.filter((t) => t.name !== form.values.name).map((t) => t.name)}
                  onChange={(relationships) => form.setFieldValue('relationships', relationships)}
                />
              </Tabs.Panel>
            </Tabs>
          </Stack>
        </Box>
      </ScrollArea>
    </Stack>
  );
}
