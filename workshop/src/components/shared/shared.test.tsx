import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ValidationBar } from './ValidationBar';
import { ConfirmDialog } from './ConfirmDialog';
import type { Diagnostic } from '@/lib/types';

describe('ValidationBar', () => {
  it('shows green check when no diagnostics', () => {
    render(<ValidationBar diagnostics={[]} />);
    expect(screen.getByText(/All validations pass/)).toBeInTheDocument();
  });

  it('shows error count when errors exist', () => {
    const diagnostics: Diagnostic[] = [
      { severity: 'error', message: 'Field name is required' },
      { severity: 'warning', message: 'Consider adding a description' },
    ];
    render(<ValidationBar diagnostics={diagnostics} />);
    expect(screen.getByText(/1 error/)).toBeInTheDocument();
    expect(screen.getByText(/1 warning/)).toBeInTheDocument();
  });

  it('shows error messages inline', () => {
    const diagnostics: Diagnostic[] = [
      { severity: 'error', message: 'Target type not found' },
    ];
    render(<ValidationBar diagnostics={diagnostics} />);
    expect(screen.getByText('Target type not found')).toBeInTheDocument();
  });

  it('shows warning bar when only warnings', () => {
    const diagnostics: Diagnostic[] = [
      { severity: 'warning', message: 'No abilities declared' },
    ];
    render(<ValidationBar diagnostics={diagnostics} />);
    expect(screen.getByText(/1 warning/)).toBeInTheDocument();
    expect(screen.getByText('No abilities declared')).toBeInTheDocument();
  });
});

describe('ConfirmDialog', () => {
  it('renders when open', () => {
    render(
      <ConfirmDialog
        open={true}
        title="Unsaved Changes"
        message="You have unsaved changes. Discard?"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.getByText('Unsaved Changes')).toBeInTheDocument();
    expect(screen.getByText('You have unsaved changes. Discard?')).toBeInTheDocument();
  });

  it('does not render when closed', () => {
    render(
      <ConfirmDialog
        open={false}
        title="Unsaved Changes"
        message="Discard?"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.queryByText('Unsaved Changes')).not.toBeInTheDocument();
  });

  it('calls onConfirm when confirm button clicked', () => {
    const onConfirm = vi.fn();
    render(
      <ConfirmDialog
        open={true}
        title="Test"
        message="Are you sure?"
        onConfirm={onConfirm}
        onCancel={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByText('Confirm'));
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it('calls onCancel when cancel button clicked', () => {
    const onCancel = vi.fn();
    render(
      <ConfirmDialog
        open={true}
        title="Test"
        message="Are you sure?"
        onConfirm={vi.fn()}
        onCancel={onCancel}
      />,
    );
    fireEvent.click(screen.getByText('Cancel'));
    expect(onCancel).toHaveBeenCalledOnce();
  });

  it('uses custom button labels', () => {
    render(
      <ConfirmDialog
        open={true}
        title="Delete"
        message="Cannot undo."
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
        confirmLabel="Yes, delete"
        cancelLabel="Go back"
        variant="danger"
      />,
    );
    expect(screen.getByText('Yes, delete')).toBeInTheDocument();
    expect(screen.getByText('Go back')).toBeInTheDocument();
  });
});
