import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { ActionsPage } from './ActionsPage';
import { RolesPage } from './RolesPage';
import { mockTypes, mockActions, mockRoles } from '@/api/mock/data';

const server = setupServer(
  http.get('/api/types', () => HttpResponse.json(mockTypes)),
  http.get('/api/actions', () => HttpResponse.json(mockActions)),
  http.get('/api/roles', () => HttpResponse.json(mockRoles)),
);

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderPage(Page: React.FC, route = '/', path = '/') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route path={path} element={<Page />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ActionsPage', () => {
  it('renders action list and editor', async () => {
    renderPage(ActionsPage, '/actions/update_tier', '/actions/:actionName');
    await waitFor(() => expect(screen.getByText('update_tier')).toBeInTheDocument());
    expect(screen.getByText('freeze_customer')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByPlaceholderText('Action name')).toHaveValue('update_tier'));
  });

  it('switches to new action form', async () => {
    renderPage(ActionsPage, '/actions', '/actions');
    await waitFor(() => expect(screen.getByText('+ New Action')).toBeInTheDocument());
    fireEvent.click(screen.getByText('+ New Action'));
    await waitFor(() => expect(screen.getByPlaceholderText('Action name')).toHaveValue(''));
  });
});

describe('RolesPage', () => {
  it('renders role list and editor', async () => {
    renderPage(RolesPage, '/roles/Admin', '/roles/:roleName');
    await waitFor(() => expect(screen.getByText('Admin')).toBeInTheDocument());
    expect(screen.getByText('SalesManager')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByPlaceholderText('Role name')).toHaveValue('Admin'));
  });

  it('switches to new role form', async () => {
    renderPage(RolesPage, '/roles', '/roles');
    await waitFor(() => expect(screen.getByText('+ New Role')).toBeInTheDocument());
    fireEvent.click(screen.getByText('+ New Role'));
    await waitFor(() => expect(screen.getByPlaceholderText('Role name')).toHaveValue(''));
  });
});
