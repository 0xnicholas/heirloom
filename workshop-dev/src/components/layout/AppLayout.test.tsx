import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AppLayout } from './AppLayout';

function renderWithProviders(initialRoute = '/schema') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[initialRoute]}>
        <AppLayout />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('AppLayout', () => {
  it('renders navigation bar with three tabs', () => {
    renderWithProviders();
    expect(screen.getByText('Schema')).toBeInTheDocument();
    expect(screen.getByText('Query')).toBeInTheDocument();
    expect(screen.getByText('Security')).toBeInTheDocument();
  });
});
