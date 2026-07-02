import { Box, Badge, Container, Paper, Table, Text, Title } from '@mantine/core';
import { mockEvents } from '@/api/mock/data';

export function EventsPage() {
  return (
    <Box style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'auto' }}>
      <Paper p="md" radius={0} style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
        <Title order={2}>Audit</Title>
        <Text size="xs" c="dimmed">Event log and change history</Text>
      </Paper>
      <Container size="lg" py="md">
        <Paper withBorder radius="md" style={{ overflow: 'hidden' }}>
          <Table verticalSpacing="xs" horizontalSpacing="md">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Time</Table.Th>
                <Table.Th>Actor</Table.Th>
                <Table.Th>Action</Table.Th>
                <Table.Th>Target</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {mockEvents.map((event) => (
                <Table.Tr key={event.id}>
                  <Table.Td>
                    <Text size="xs" c="dimmed" ff="monospace">{event.timestamp}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{event.actor}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Badge size="sm" color="indigo" variant="light">
                      {event.action}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" ff="monospace">{event.target}</Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Paper>
      </Container>
    </Box>
  );
}
