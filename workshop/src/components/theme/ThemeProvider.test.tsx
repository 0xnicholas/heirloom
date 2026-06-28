import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AppLayout } from '@/components/layout/AppLayout';
import { ThemeProvider } from './ThemeProvider';

const STORAGE_KEY = 'heirloom-theme';

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

describe('ThemeProvider', () => {
  beforeEach(() => {
    document.documentElement.removeAttribute('data-theme');
    window.localStorage.removeItem(STORAGE_KEY);
  });

  it('toggles light and dark theme via the top bar button', () => {
    renderWithProviders();
    const toggle = screen.getByLabelText('Toggle theme');

    expect(document.documentElement.getAttribute('data-theme')).toBe('light');

    fireEvent.click(toggle);
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');

    fireEvent.click(toggle);
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('persists theme preference to localStorage when toggled', () => {
    renderWithProviders();
    const toggle = screen.getByLabelText('Toggle theme');

    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();

    fireEvent.click(toggle);
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe('dark');

    fireEvent.click(toggle);
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe('light');
  });
});
