import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { RoleList } from './RoleList';
import { ActionList } from './ActionList';
import type { Role, Action } from '@/lib/types';
import { theme } from '@/lib/theme';

function renderWithMantine(ui: React.ReactNode) {
  return render(
    <MantineProvider theme={theme} defaultColorScheme="light">
      {ui}
    </MantineProvider>,
  );
}

describe('RoleList', () => {
  it('renders roles', () => {
    const roles: Role[] = [
      {
        name: 'Admin',
        scope: 'Ontology',
        targets: [],
        capabilities: [],
        actors: [],
      },
    ];
    renderWithMantine(
      <RoleList
        roles={roles}
        selected={null}
        onSelect={vi.fn()}
        onNew={vi.fn()}
      />,
    );
    expect(screen.getByText('Admin')).toBeInTheDocument();
  });

  it('shows empty state when no roles', () => {
    renderWithMantine(
      <RoleList
        roles={[]}
        selected={null}
        onSelect={vi.fn()}
        onNew={vi.fn()}
      />,
    );
    expect(screen.getByText('No roles found')).toBeInTheDocument();
  });

  it('marks selected role via data attribute', () => {
    const roles: Role[] = [
      {
        name: 'Admin',
        scope: 'Ontology',
        targets: [],
        capabilities: [],
        actors: [],
      },
      {
        name: 'Sales',
        scope: 'Type',
        targets: [],
        capabilities: [],
        actors: [],
      },
    ];
    renderWithMantine(
      <RoleList
        roles={roles}
        selected="Sales"
        onSelect={vi.fn()}
        onNew={vi.fn()}
      />,
    );
    const salesBtn = screen.getByText('Sales').closest('button');
    expect(salesBtn).toHaveAttribute('data-selected');
  });
});

describe('ActionList', () => {
  it('renders actions', () => {
    const actions: Action[] = [
      {
        name: 'update_tier',
        targetType: 'Customer',
        requires: 'mutate',
        parameters: [],
        validateRules: [],
        executeTemplate: '',
      },
    ];
    renderWithMantine(
      <ActionList
        actions={actions}
        selected={null}
        onSelect={vi.fn()}
        onNew={vi.fn()}
      />,
    );
    expect(screen.getByText('update_tier')).toBeInTheDocument();
  });

  it('shows target type and requires info', () => {
    const actions: Action[] = [
      {
        name: 'approve_order',
        targetType: 'Order',
        requires: 'mutate',
        parameters: [],
        validateRules: [],
        executeTemplate: '',
      },
    ];
    renderWithMantine(
      <ActionList
        actions={actions}
        selected={null}
        onSelect={vi.fn()}
        onNew={vi.fn()}
      />,
    );
    expect(screen.getByText('approve_order')).toBeInTheDocument();
    expect(screen.getByText(/Order/)).toBeInTheDocument();
    expect(screen.getByText(/mutate/)).toBeInTheDocument();
  });

  it('shows empty state when no actions', () => {
    renderWithMantine(
      <ActionList
        actions={[]}
        selected={null}
        onSelect={vi.fn()}
        onNew={vi.fn()}
      />,
    );
    expect(screen.getByText('No actions found')).toBeInTheDocument();
  });
});
