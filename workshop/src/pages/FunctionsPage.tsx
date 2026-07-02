import { Box, Badge, Card, Code, Container, Paper, SimpleGrid, Text, Title } from '@mantine/core';
import { mockFunctions } from '@/api/mock/data';

export function FunctionsPage() {
  return (
    <Box style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'auto' }}>
      <Paper p="md" radius={0} style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
        <Title order={2}>Functions</Title>
        <Text size="xs" c="dimmed">Reusable query and compute functions</Text>
      </Paper>
      <Container size="xl" py="md">
        <SimpleGrid cols={{ base: 1, md: 2, lg: 3 }} spacing="md">
          {mockFunctions.map((fn) => (
            <Card key={fn.name} withBorder shadow="sm" padding="md" radius="md">
              <Card.Section withBorder inheritPadding py="xs">
                <Text size="sm" fw={500} ff="monospace">{fn.name}</Text>
                <Badge size="xs" variant="light" mt={4}>{fn.language}</Badge>
              </Card.Section>
              <Text size="sm" c="dimmed" mt="sm">
                {fn.description}
              </Text>
              <Code block fz="xs" mt="sm">
                {fn.signature}
              </Code>
            </Card>
          ))}
        </SimpleGrid>
      </Container>
    </Box>
  );
}
