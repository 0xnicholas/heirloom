import { useMantineColorScheme, Container, Stack, Title, Text, Paper, SegmentedControl, Select, Anchor, Group } from '@mantine/core';
import { IconExternalLink } from '@tabler/icons-react';
import { API_DOCS_URL, DEFAULT_ONTOLOGY, ONTOLOGIES } from '@/lib/constants';

export function SettingsPage() {
  const { colorScheme, setColorScheme } = useMantineColorScheme();

  return (
    <Container size="md" py="md">
      <Stack gap="lg">
        <div>
          <Title order={1}>Settings</Title>
          <Text c="dimmed" size="sm">Workshop preferences</Text>
        </div>

        <Paper p="md" withBorder>
          <Stack gap="sm">
            <Title order={3} size="h4">Appearance</Title>
            <Text size="sm" c="dimmed">Theme</Text>
            <SegmentedControl
              value={colorScheme}
              onChange={(value) => setColorScheme(value as 'light' | 'dark' | 'auto')}
              data={[
                { value: 'light', label: 'Light' },
                { value: 'dark', label: 'Dark' },
                { value: 'auto', label: 'Auto' },
              ]}
            />
          </Stack>
        </Paper>

        <Paper p="md" withBorder>
          <Stack gap="sm">
            <Title order={3} size="h4">Workspace</Title>
            <Text size="sm" c="dimmed">Active ontology</Text>
            <Select
              defaultValue={DEFAULT_ONTOLOGY}
              data={ONTOLOGIES.map((name) => ({ value: name, label: name }))}
              allowDeselect={false}
            />
          </Stack>
        </Paper>

        <Paper p="md" withBorder>
          <Stack gap="sm">
            <Title order={3} size="h4">Documentation</Title>
            <Anchor
              href={API_DOCS_URL}
              target="_blank"
              rel="noreferrer"
              size="sm"
            >
              <Group gap={4} align="center">
                <span>Open API Docs</span>
                <IconExternalLink size={14} />
              </Group>
            </Anchor>
          </Stack>
        </Paper>
      </Stack>
    </Container>
  );
}
