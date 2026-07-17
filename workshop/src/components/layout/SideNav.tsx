import { NavLink } from 'react-router-dom';
import { AppShell, NavLink as MantineNavLink, Stack, Text } from '@mantine/core';
import {
  IconChartBar,
  IconSearch,
  IconStack2,
  IconHammer,
  IconList,
  IconGitMerge,
  IconHierarchy2,
  IconPlayerPlay,
  IconCode,
  IconShieldCheck,
  IconCheckbox,
  IconFileText,
  IconUsers,
  IconClock,
  IconAlertTriangle,
  IconInbox,
  IconTerminal,
  IconCommand,
  IconMessage,
  IconSettings,
  type Icon,
} from '@tabler/icons-react';

interface NavItem {
  to: string;
  label: string;
  icon: Icon;
}

interface NavGroup {
  title: string;
  items: NavItem[];
}

const groups: NavGroup[] = [
  {
    title: 'Overview',
    items: [
      { to: '/', label: 'Stats', icon: IconChartBar },
      { to: '/explorer', label: 'Explorer', icon: IconSearch },
    ],
  },
  {
    title: 'Design',
    items: [
      { to: '/schema', label: 'Schema', icon: IconStack2 },
      { to: '/builder', label: 'Builder', icon: IconHammer },
      { to: '/attributes', label: 'Attributes', icon: IconList },
      { to: '/relationships', label: 'Relationships', icon: IconGitMerge },
      { to: '/graph', label: 'Graphs', icon: IconHierarchy2 },
    ],
  },
  {
    title: 'Automation',
    items: [
      { to: '/actions', label: 'Actions', icon: IconPlayerPlay },
      { to: '/functions', label: 'Functions', icon: IconCode },
      { to: '/rules', label: 'Rules', icon: IconShieldCheck },
      { to: '/tasks', label: 'Tasks', icon: IconCheckbox },
      { to: '/forms', label: 'Forms', icon: IconFileText },
    ],
  },
  {
    title: 'Governance',
    items: [
      { to: '/roles', label: 'Roles', icon: IconUsers },
      { to: '/events', label: 'Audit', icon: IconClock },
      { to: '/violations', label: 'Violations', icon: IconAlertTriangle },
      { to: '/inbox', label: 'Inbox', icon: IconInbox },
    ],
  },
  {
    title: 'Agent',
    items: [
      { to: '/queries', label: 'Queries', icon: IconTerminal },
      { to: '/console', label: 'Console', icon: IconCommand },
      { to: '/chat', label: 'Chat', icon: IconMessage },
    ],
  },
  {
    title: 'System',
    items: [{ to: '/settings', label: 'Settings', icon: IconSettings }],
  },
];

export function SideNav() {
  return (
    <AppShell.Navbar p="xs">
      {groups.map((group) => (
        <Stack key={group.title} gap={4} mb="md">
          <Text
            size="xs"
            fw={600}
            c="dimmed"
            tt="uppercase"
            lts={0.5}
            px="sm"
            py={2}
          >
            {group.title}
          </Text>
          {group.items.map((item) => (
            <MantineNavLink
              key={item.to}
              component={NavLink}
              to={item.to}
              end={item.to === '/'}
              label={item.label}
              leftSection={<item.icon size={16} stroke={1.5} />}
              variant="light"
            />
          ))}
        </Stack>
      ))}
    </AppShell.Navbar>
  );
}
