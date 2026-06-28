import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@/components/theme/ThemeProvider';
import { StatsPage } from '@/pages/StatsPage';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { mockTypes, mockActions, mockRoles } from '@/api/mock/data';

const server = setupServer(
  http.get('/api/types', () => HttpResponse.json(mockTypes)),
  http.get('/api/actions', () => HttpResponse.json(mockActions)),
  http.get('/api/roles', () => HttpResponse.json(mockRoles)),
);

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ThemeProvider>
        <MemoryRouter>
          <StatsPage />
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>
  );
}

describe('StatsPage', () => {
  it('renders dashboard sections and metric cards', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByText('Resource Types')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('metric-resource-types').textContent).toContain('4'));
    expect(screen.getByText('Relationships')).toBeInTheDocument();
    expect(screen.getByText('What is this?')).toBeInTheDocument();
    expect(screen.getByText('Ontology Graph')).toBeInTheDocument();
  });

  it('lists resource types with versions', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('Resource Types (4)')).toBeInTheDocument());
    const panel = screen.getByText('Resource Types (4)').closest('div')?.parentElement;
    expect(panel).toBeTruthy();
    expect(panel?.textContent).toContain('Customer');
    expect(panel?.textContent).toContain('Order');
  });
});
