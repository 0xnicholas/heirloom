import { useState } from 'react';
import { Paper, Group, Title, Text, Select } from '@mantine/core';

type Level = 'Simple' | 'Technical' | 'Expert';

const explanations: Record<Level, string> = {
  Simple:
    "This is like your business world map — it shows what kinds of things exist (like Customers and Orders), how many there are, and how everything connects together.",
  Technical:
    "The Stats dashboard summarizes the ontology: Resource Types define the schema, Resource Instances are the data, and Relationships model how entities interact. The graph visualizes the type graph for quick orientation.",
  Expert:
    "This view aggregates schema registry metadata — type cardinality, relationship cardinality, ability matrices, and state machine coverage — into a system-level health dashboard with an interactive force-directed type graph.",
};

export function ELI5Card() {
  const [level, setLevel] = useState<Level>('Simple');

  return (
    <Paper withBorder shadow="sm" radius="md" p="md">
      <Group justify="space-between" align="flex-start" mb="xs">
        <Title order={3} size="h4">What is this?</Title>
        <Select
          size="xs"
          value={level}
          onChange={(v) => v && setLevel(v as Level)}
          data={[
            { value: 'Simple', label: 'Simple (ELI5)' },
            { value: 'Technical', label: 'Technical' },
            { value: 'Expert', label: 'Expert' },
          ]}
          allowDeselect={false}
          comboboxProps={{ withinPortal: true }}
          w={150}
        />
      </Group>
      <Text size="sm" c="dimmed" style={{ lineHeight: 1.6 }}>
        {explanations[level]}
      </Text>
    </Paper>
  );
}
