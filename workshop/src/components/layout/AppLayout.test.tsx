import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@/components/theme/ThemeProvider';
import { AppLayout } from './AppLayout';

function renderWithProviders(initialRoute = '/') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ThemeProvider>
        <MemoryRouter initialEntries={[initialRoute]}>
          <AppLayout />
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>
  );
}

describe('AppLayout', () => {
  it('renders top bar with logo and theme toggle', () => {
    renderWithProviders();
    expect(screen.getByText('Heirloom')).toBeInTheDocument();
    expect(screen.getByLabelText('Toggle theme')).toBeInTheDocument();
  });

  it('renders sidebar navigation groups', () => {
    renderWithProviders();
    expect(screen.getByText('Overview')).toBeInTheDocument();
    expect(screen.getByText('Stats')).toBeInTheDocument();
    expect(screen.getByText('Schema')).toBeInTheDocument();
    expect(screen.getByText('Agent')).toBeInTheDocument();
    expect(screen.getByText('Settings')).toBeInTheDocument();
  });
});
