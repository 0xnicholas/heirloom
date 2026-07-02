import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { ValidationBar } from './ValidationBar';
import type { Diagnostic } from '@/lib/types';
import { theme } from '@/lib/theme';

function renderWithMantine(ui: React.ReactNode) {
  return render(
    <MantineProvider theme={theme} defaultColorScheme="light">
      {ui}
    </MantineProvider>,
  );
}

describe('ValidationBar', () => {
  it('shows green check when no diagnostics', () => {
    renderWithMantine(<ValidationBar diagnostics={[]} />);
    expect(screen.getByText(/All validations pass/)).toBeInTheDocument();
  });

  it('shows error count when errors exist', () => {
    const diagnostics: Diagnostic[] = [
      { severity: 'error', message: 'Field name is required' },
      { severity: 'warning', message: 'Consider adding a description' },
    ];
    renderWithMantine(<ValidationBar diagnostics={diagnostics} />);
    expect(screen.getByText(/1 error/)).toBeInTheDocument();
    expect(screen.getByText(/1 warning/)).toBeInTheDocument();
  });

  it('shows error messages inline', () => {
    const diagnostics: Diagnostic[] = [
      { severity: 'error', message: 'Target type not found' },
    ];
    renderWithMantine(<ValidationBar diagnostics={diagnostics} />);
    expect(screen.getByText('Target type not found')).toBeInTheDocument();
  });

  it('shows warning bar when only warnings', () => {
    const diagnostics: Diagnostic[] = [
      { severity: 'warning', message: 'No abilities declared' },
    ];
    renderWithMantine(<ValidationBar diagnostics={diagnostics} />);
    expect(screen.getByText(/1 warning/)).toBeInTheDocument();
    expect(screen.getByText('No abilities declared')).toBeInTheDocument();
  });
});
