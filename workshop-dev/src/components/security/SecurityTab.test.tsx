import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { RoleList } from './RoleList';
import { ActionList } from './ActionList';
import type { Role, Action } from '@/lib/types';

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
    render(
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
    render(
      <RoleList
        roles={[]}
        selected={null}
        onSelect={vi.fn()}
        onNew={vi.fn()}
      />,
    );
    expect(screen.getByText('No roles found')).toBeInTheDocument();
  });

  it('highlights selected role', () => {
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
    render(
      <RoleList
        roles={roles}
        selected="Sales"
        onSelect={vi.fn()}
        onNew={vi.fn()}
      />,
    );
    const salesBtn = screen.getByText('Sales').closest('button');
    expect(salesBtn?.className).toContain('indigo-50');
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
    render(
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
    render(
      <ActionList
        actions={actions}
        selected={null}
        onSelect={vi.fn()}
        onNew={vi.fn()}
      />,
    );
    expect(screen.getByText('approve_order')).toBeInTheDocument();
    // The component renders targetType and requires in a sub-line
    expect(screen.getByText(/Order/)).toBeInTheDocument();
    expect(screen.getByText(/mutate/)).toBeInTheDocument();
  });

  it('shows empty state when no actions', () => {
    render(
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
