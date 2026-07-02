import type { ReactNode } from 'react';
import { Stack, Title, Text, Center, Box } from '@mantine/core';
import { IconDotsCircleHorizontal } from '@tabler/icons-react';

interface PlaceholderPageProps {
  title: string;
  description: string;
  icon?: ReactNode;
}

export function PlaceholderPage({ title, description, icon }: PlaceholderPageProps) {
  return (
    <Center h="100%" p="xl">
      <Stack align="center" gap="md" maw={480}>
        <Box
          style={{
            width: 48,
            height: 48,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            borderRadius: '50%',
            backgroundColor: 'var(--mantine-color-gray-1)',
            color: 'var(--mantine-color-dimmed)',
          }}
        >
          {icon ?? <IconDotsCircleHorizontal size={24} />}
        </Box>
        <Title order={2} ta="center">{title}</Title>
        <Text c="dimmed" size="sm" ta="center">
          {description}
        </Text>
      </Stack>
    </Center>
  );
}
