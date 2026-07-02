import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { QueryHistory } from './QueryHistory';
import { QueryResults } from './QueryResults';
import type { SavedQuery, QueryResult } from '@/lib/types';
import { theme } from '@/lib/theme';

function renderWithMantine(ui: React.ReactNode) {
  return render(
    <MantineProvider theme={theme} defaultColorScheme="light">
      {ui}
    </MantineProvider>,
  );
}

describe('QueryHistory', () => {
  it('renders saved queries', () => {
    const queries: SavedQuery[] = [
      {
        id: '1',
        name: 'All Customers',
        query: { from: 'Customer', limit: 10 },
        createdAt: '2026-01-01',
        favorited: false,
      },
    ];
    renderWithMantine(
      <QueryHistory
        queries={queries}
        onSelect={vi.fn()}
        onDelete={vi.fn()}
        onToggleFavorite={vi.fn()}
      />,
    );
    expect(screen.getByText('All Customers')).toBeInTheDocument();
  });

  it('shows empty state when no queries', () => {
    renderWithMantine(
      <QueryHistory
        queries={[]}
        onSelect={vi.fn()}
        onDelete={vi.fn()}
        onToggleFavorite={vi.fn()}
      />,
    );
    expect(screen.getByText('No saved queries')).toBeInTheDocument();
  });

  it('renders search input', () => {
    renderWithMantine(
      <QueryHistory
        queries={[]}
        onSelect={vi.fn()}
        onDelete={vi.fn()}
        onToggleFavorite={vi.fn()}
      />,
    );
    expect(screen.getByPlaceholderText('Search queries...')).toBeInTheDocument();
  });
});

describe('QueryResults', () => {
  it('renders table with rows', () => {
    const result: QueryResult = {
      rows: [
        {
          name: 'Acme',
          tier: 'enterprise',
          _meta: { rid: 'ri.customer.1', type: 'Customer', version: 1, state: 'Active' },
        },
      ],
      total: 1,
      meta: { query_ms: 12, plan: 'mock' },
    };
    renderWithMantine(<QueryResults result={result} />);
    expect(screen.getByText('Acme')).toBeInTheDocument();
    expect(screen.getByText('enterprise')).toBeInTheDocument();
  });

  it('shows empty state when no result', () => {
    renderWithMantine(<QueryResults result={null} />);
    expect(screen.getByText(/run a query/i)).toBeInTheDocument();
  });

  it('shows no rows message on empty result', () => {
    const result: QueryResult = {
      rows: [],
      total: 0,
      meta: { query_ms: 5, plan: 'mock' },
    };
    renderWithMantine(<QueryResults result={result} />);
    expect(screen.getByText(/no rows returned/i)).toBeInTheDocument();
  });
});
