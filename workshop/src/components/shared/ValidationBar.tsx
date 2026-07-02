import { Alert, Group, Stack, Text, Badge } from '@mantine/core';
import { IconAlertCircle, IconAlertTriangle, IconCheck } from '@tabler/icons-react';
import type { Diagnostic } from '@/lib/types';

interface ValidationBarProps {
  diagnostics: Diagnostic[];
}

export function ValidationBar({ diagnostics }: ValidationBarProps) {
  if (diagnostics.length === 0) {
    return (
      <Alert color="green" variant="light" icon={<IconCheck size={16} />}>
        All validations pass
      </Alert>
    );
  }

  const errors = diagnostics.filter((d) => d.severity === 'error');
  const warnings = diagnostics.filter((d) => d.severity === 'warning');
  const hasErrors = errors.length > 0;

  return (
    <Stack gap="xs">
      <Alert
        color={hasErrors ? 'red' : 'yellow'}
        variant="light"
        icon={hasErrors ? <IconAlertCircle size={16} /> : <IconAlertTriangle size={16} />}
      >
        <Group gap="xs">
          {errors.length > 0 && (
            <Badge color="red" variant="filled" size="sm">
              {errors.length} error{errors.length > 1 ? 's' : ''}
            </Badge>
          )}
          {warnings.length > 0 && (
            <Badge color="yellow" variant="filled" size="sm">
              {warnings.length} warning{warnings.length > 1 ? 's' : ''}
            </Badge>
          )}
        </Group>
      </Alert>
      <Stack gap={2}>
        {diagnostics.map((d, i) => (
          <Text
            key={i}
            size="xs"
            c={d.severity === 'error' ? 'red' : d.severity === 'warning' ? 'yellow.7' : 'blue'}
            pl="sm"
          >
            {d.message}
          </Text>
        ))}
      </Stack>
    </Stack>
  );
}
