import { Box, Badge, Container, Paper, Stack, Table, Text, Title } from '@mantine/core';
import { useSchemaRegistry } from '@/hooks/useSchemaRegistry';

export function AttributesPage() {
  const { typesQuery } = useSchemaRegistry();
  const types = typesQuery.data || [];

  return (
    <Box style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'auto' }}>
      <Paper p="md" radius={0} style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
        <Title order={2}>Attributes</Title>
        <Text size="xs" c="dimmed">All fields declared across resource types</Text>
      </Paper>
      <Container size="lg" py="md">
        <Stack gap="lg">
          {types.map((type) => (
            <Paper key={type.name} withBorder radius="md" style={{ overflow: 'hidden' }}>
              <Box p="sm" style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
                <Title order={4} size="h5">{type.name}</Title>
                <Text size="xs" c="dimmed">
                  {type.fields.length} field{type.fields.length !== 1 ? 's' : ''}
                </Text>
              </Box>
              <Table verticalSpacing="xs" horizontalSpacing="md">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Name</Table.Th>
                    <Table.Th>Type</Table.Th>
                    <Table.Th>Required</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {type.fields.map((field) => (
                    <Table.Tr key={field.name}>
                      <Table.Td>
                        <Text size="xs" ff="monospace">{field.name}</Text>
                      </Table.Td>
                      <Table.Td>
                        <Badge size="sm" color="indigo" variant="light">
                          {field.type}
                        </Badge>
                      </Table.Td>
                      <Table.Td>
                        <Text size="sm" c="dimmed">{field.required ? 'Yes' : 'No'}</Text>
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Paper>
          ))}
        </Stack>
      </Container>
    </Box>
  );
}
