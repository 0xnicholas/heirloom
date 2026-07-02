import { useState, useEffect, useCallback } from 'react';
import { Outlet } from 'react-router-dom';
import { AppShell, Button, Text, useMantineColorScheme } from '@mantine/core';
import { IconTerminal } from '@tabler/icons-react';
import { TopBar } from './TopBar';
import { SideNav } from './SideNav';
import { QueryConsole } from './QueryConsole';
import { ConsoleContext } from './ConsoleContext';

export function AppLayout() {
  const [consoleOpen, setConsoleOpen] = useState(false);
  const [consoleHeight, setConsoleHeight] = useState(50);
  const [activeType, setActiveType] = useState<string | null>(null);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const { colorScheme } = useMantineColorScheme();

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.key === '`') {
        e.preventDefault();
        setConsoleOpen((prev) => !prev);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  const toggleConsole = useCallback(() => setConsoleOpen((prev) => !prev), []);

  return (
    <ConsoleContext.Provider value={{ activeType, setActiveType }}>
      <AppShell
        header={{ height: 56 }}
        navbar={{ width: 224, breakpoint: 'sm', collapsed: { mobile: !mobileNavOpen } }}
        footer={{ height: 28 }}
        padding={0}
      >
        <AppShell.Header withBorder>
          <TopBar onBurgerClick={() => setMobileNavOpen((o) => !o)} />
        </AppShell.Header>

        <AppShell.Navbar withBorder>
          <SideNav />
        </AppShell.Navbar>

        <AppShell.Main
          style={{
            display: 'flex',
            flexDirection: 'column',
            backgroundColor: colorScheme === 'dark' ? 'var(--mantine-color-dark-7)' : 'var(--mantine-color-gray-0)',
          }}
        >
          <div style={{ flex: 1, overflow: 'hidden' }}>
            <Outlet />
          </div>
          {consoleOpen && (
            <QueryConsole
              height={consoleHeight}
              onHeightChange={setConsoleHeight}
              onClose={() => setConsoleOpen(false)}
              defaultFrom={activeType ?? undefined}
            />
          )}
        </AppShell.Main>

        <AppShell.Footer
          withBorder
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '0 16px',
          }}
        >
          <Button
            onClick={toggleConsole}
            variant="subtle"
            size="compact-xs"
            leftSection={<IconTerminal size={12} />}
          >
            Query Console
          </Button>
          <Text size="xs" c="dimmed">Ctrl+`</Text>
        </AppShell.Footer>
      </AppShell>
    </ConsoleContext.Provider>
  );
}
