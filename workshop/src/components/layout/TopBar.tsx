import { useMantineColorScheme, ActionIcon, Group, Menu, Button, Burger } from '@mantine/core';
import { IconHexagons, IconDatabase, IconChevronDown, IconSun, IconMoon, IconBook, IconExternalLink } from '@tabler/icons-react';
import { API_DOCS_URL, DEFAULT_ONTOLOGY, ONTOLOGIES } from '@/lib/constants';

interface TopBarProps {
  onBurgerClick?: () => void;
  burgerOpen?: boolean;
}

export function TopBar({ onBurgerClick, burgerOpen }: TopBarProps) {
  const { colorScheme, toggleColorScheme } = useMantineColorScheme();
  const isDark = colorScheme === 'dark';

  return (
    <Group
      h={56}
      px="md"
      justify="space-between"
      style={{
        borderBottom: '1px solid var(--mantine-color-gray-3)',
        backgroundColor: 'var(--mantine-color-body)',
      }}
    >
      <Group gap="sm">
        {onBurgerClick && (
          <Burger
            opened={burgerOpen ?? false}
            onClick={onBurgerClick}
            hiddenFrom="sm"
            size="sm"
          />
        )}
        <Group gap={6} align="center">
          <IconHexagons size={20} color="var(--mantine-color-indigo-6)" />
          <span style={{ fontWeight: 600, fontSize: 14 }}>Heirloom</span>
          <span style={{ fontSize: 12, color: 'var(--mantine-color-dimmed)' }}>Workshop</span>
        </Group>
      </Group>

      <Group gap="sm">
        <Menu shadow="md" width={200}>
          <Menu.Target>
            <Button
              variant="default"
              size="xs"
              leftSection={<IconDatabase size={14} />}
              rightSection={<IconChevronDown size={12} />}
            >
              {DEFAULT_ONTOLOGY}
            </Button>
          </Menu.Target>
          <Menu.Dropdown>
            {ONTOLOGIES.map((name) => (
              <Menu.Item key={name}>{name}</Menu.Item>
            ))}
          </Menu.Dropdown>
        </Menu>

        <Button
          component="a"
          href={API_DOCS_URL}
          target="_blank"
          rel="noreferrer"
          variant="subtle"
          size="xs"
          leftSection={<IconBook size={14} />}
          rightSection={<IconExternalLink size={12} />}
        >
          API Docs
        </Button>

        <ActionIcon
          onClick={toggleColorScheme}
          aria-label="Toggle theme"
          variant="subtle"
          size="lg"
        >
          {isDark ? <IconSun size={16} /> : <IconMoon size={16} />}
        </ActionIcon>
      </Group>
    </Group>
  );
}
