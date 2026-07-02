import { Checkbox, SimpleGrid, Stack, Text, Card } from '@mantine/core';
import type { Ability } from '@/lib/types';
import { ABILITIES } from '@/lib/constants';

interface AbilitiesMatrixProps {
  selected: Ability[];
  onChange: (abilities: Ability[]) => void;
}

export function AbilitiesMatrix({ selected, onChange }: AbilitiesMatrixProps) {
  return (
    <Stack gap="xs">
      <Text size="sm" fw={600}>Abilities</Text>
      <SimpleGrid cols={2} spacing="xs">
        {ABILITIES.map((ability) => {
          const isChecked = selected.includes(ability);
          return (
            <Card
              key={ability}
              p="xs"
              withBorder
              style={{
                cursor: 'pointer',
                backgroundColor: isChecked ? 'var(--mantine-color-indigo-0)' : undefined,
                borderColor: isChecked ? 'var(--mantine-color-indigo-4)' : undefined,
              }}
              onClick={() => {
                onChange(
                  isChecked
                    ? selected.filter((a) => a !== ability)
                    : [...selected, ability],
                );
              }}
            >
              <Checkbox
                checked={isChecked}
                onChange={() => {}}
                label={ability}
                size="sm"
                styles={{ label: { fontFamily: 'monospace', fontSize: 12 } }}
              />
            </Card>
          );
        })}
      </SimpleGrid>
    </Stack>
  );
}
