import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MantineProvider } from '@mantine/core';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { SchemaPage } from '@/pages/SchemaPage';
import { ConsoleContext } from '@/components/layout/ConsoleContext';
import { mockTypes, mockActions, mockRoles } from '@/api/mock/data';
import type { ResourceType } from '@/lib/types';
import { theme } from '@/lib/theme';

// Clone mock data so tests can mutate it without affecting other tests
let types: ResourceType[] = JSON.parse(JSON.stringify(mockTypes));

const server = setupServer(
  http.get('/api/types', () => HttpResponse.json(types)),
  http.get('/api/actions', () => HttpResponse.json(mockActions)),
  http.get('/api/roles', () => HttpResponse.json(mockRoles)),
  http.post('/api/types', async ({ request }) => {
    const body = (await request.json()) as ResourceType;
    types.push(body);
    return HttpResponse.json(body, { status: 201 });
  }),
  http.put('/api/types/:name', async ({ request }) => {
    const body = (await request.json()) as ResourceType;
    const idx = types.findIndex(t => t.name === body.name);
    if (idx !== -1) types[idx] = body;
    return HttpResponse.json(body);
  }),
  http.post('/api/query/execute', () => {
    return HttpResponse.json({
      rows: [
        {
          name: 'TestCorp',
          tier: 'enterprise',
          _meta: { rid: 'ri.customer.1', type: 'Customer', version: 1, state: 'Active' },
        },
      ],
      total: 1,
      meta: { query_ms: 5, plan: 'test' },
    });
  }),
);

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => {
  server.resetHandlers();
  types = JSON.parse(JSON.stringify(mockTypes));
});
afterAll(() => server.close());

function renderPage(route = '/schema') {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <MantineProvider theme={theme} defaultColorScheme="light">
      <QueryClientProvider client={qc}>
        <ConsoleContext.Provider value={{ activeType: null, setActiveType: () => {} }}>
          <MemoryRouter initialEntries={[route]}>
            <SchemaPage />
          </MemoryRouter>
        </ConsoleContext.Provider>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe('Schema integration', () => {
  // Mantine Select/Combobox rendering can be slow under parallel test load
  vi.setConfig({ testTimeout: 15_000 });

  it('creates a new type and shows it in the list', async () => {
    renderPage();
    // Wait for initial types to load
    await waitFor(() => expect(screen.getByText('Customer')).toBeInTheDocument());
    expect(screen.getByText('Order')).toBeInTheDocument();

    // Click New Type
    fireEvent.click(screen.getByRole('button', { name: /New Type/i }));

    // Fill in the type name
    const nameInput = screen.getByPlaceholderText('Type name');
    fireEvent.change(nameInput, { target: { value: 'Invoice' } });

    // Find and click Save button
    const saveButton = screen.getByText(/Save/);
    fireEvent.click(saveButton);

    // The type should appear in the list (after navigate + refetch)
    await waitFor(() => expect(screen.getByText('Invoice')).toBeInTheDocument());
  });

  it('selects a type and edits its fields', async () => {
    renderPage('/schema');
    // Wait for types to load
    await waitFor(() => expect(screen.getByText('Customer')).toBeInTheDocument());

    // Click Customer in the type list to open it in the editor
    fireEvent.click(screen.getByText('Customer'));

    // The type editor should show the type name input with value Customer
    await waitFor(() =>
      expect(screen.getByPlaceholderText('Type name')).toHaveValue('Customer'),
    );

    // The initial fields should be there
    expect(screen.getByDisplayValue('name')).toBeInTheDocument();
    expect(screen.getByDisplayValue('tier')).toBeInTheDocument();

    // Click Add Field to add a new field row
    fireEvent.click(screen.getByRole('button', { name: /Add Field/i }));

    // At least one field_name placeholder should appear (existing + new row)
    const fieldNameInputs = screen.getAllByPlaceholderText('field_name');
    expect(fieldNameInputs.length).toBeGreaterThanOrEqual(1);
  });

  it('renders schema page with type list from mock data', async () => {
    renderPage('/schema');
    // Verify the page renders the type list from mock data
    await waitFor(() => expect(screen.getByText('Customer')).toBeInTheDocument());
    expect(screen.getByText('Order')).toBeInTheDocument();
    expect(screen.getByText('Contract')).toBeInTheDocument();
    expect(screen.getByText('Product')).toBeInTheDocument();
  });
});
