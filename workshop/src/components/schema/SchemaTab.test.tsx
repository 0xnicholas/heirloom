import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { TypeList } from './TypeList';
import { AbilitiesMatrix } from './AbilitiesMatrix';
import { FieldTable } from './FieldTable';
import type { ResourceType, Field } from '@/lib/types';
import { ABILITIES } from '@/lib/constants';
import { theme } from '@/lib/theme';

function renderWithMantine(ui: React.ReactNode) {
  return render(
    <MantineProvider theme={theme} defaultColorScheme="light">
      {ui}
    </MantineProvider>,
  );
}

const mockTypes: ResourceType[] = [
  {
    name: 'Customer',
    fields: [{ name: 'name', type: 'string', required: true }],
    abilities: ['key', 'query'],
    stateMachine: [],
    relationships: [],
    version: 1,
  },
  {
    name: 'Order',
    fields: [{ name: 'total', type: 'number', required: true }],
    abilities: ['key', 'query', 'mutate'],
    stateMachine: [],
    relationships: [],
    version: 1,
  },
];

describe('TypeList', () => {
  it('renders all types', () => {
    renderWithMantine(<TypeList types={mockTypes} selected={null} onSelect={vi.fn()} onNew={vi.fn()} />);
    expect(screen.getByText('Customer')).toBeInTheDocument();
    expect(screen.getByText('Order')).toBeInTheDocument();
  });

  it('calls onSelect when type clicked', () => {
    const onSelect = vi.fn();
    renderWithMantine(<TypeList types={mockTypes} selected={null} onSelect={onSelect} onNew={vi.fn()} />);
    fireEvent.click(screen.getByText('Customer'));
    expect(onSelect).toHaveBeenCalledWith('Customer');
  });

  it('filters types by search', () => {
    renderWithMantine(<TypeList types={mockTypes} selected={null} onSelect={vi.fn()} onNew={vi.fn()} />);
    const input = screen.getByPlaceholderText('Search types...');
    fireEvent.change(input, { target: { value: 'Ord' } });
    expect(screen.getByText('Order')).toBeInTheDocument();
    expect(screen.queryByText('Customer')).not.toBeInTheDocument();
  });

  it('shows no types found when filter matches nothing', () => {
    renderWithMantine(<TypeList types={mockTypes} selected={null} onSelect={vi.fn()} onNew={vi.fn()} />);
    const input = screen.getByPlaceholderText('Search types...');
    fireEvent.change(input, { target: { value: 'zzz' } });
    expect(screen.getByText('No types found')).toBeInTheDocument();
  });

  it('calls onNew when New Type button clicked', () => {
    const onNew = vi.fn();
    renderWithMantine(<TypeList types={mockTypes} selected={null} onSelect={vi.fn()} onNew={onNew} />);
    fireEvent.click(screen.getByRole('button', { name: /New Type/i }));
    expect(onNew).toHaveBeenCalledOnce();
  });

  it('marks selected type via data attribute', () => {
    renderWithMantine(<TypeList types={mockTypes} selected="Customer" onSelect={vi.fn()} onNew={vi.fn()} />);
    const customerBtn = screen.getByText('Customer').closest('button');
    expect(customerBtn).toHaveAttribute('data-selected');
  });
});

describe('AbilitiesMatrix', () => {
  it('renders all 8 ability labels', () => {
    const onChange = vi.fn();
    renderWithMantine(<AbilitiesMatrix selected={['key', 'query']} onChange={onChange} />);
    ABILITIES.forEach((a) => {
      expect(screen.getByText(a)).toBeInTheDocument();
    });
  });

  it('checks selected abilities via checkbox', () => {
    const onChange = vi.fn();
    renderWithMantine(<AbilitiesMatrix selected={['key']} onChange={onChange} />);
    const keyCheckbox = screen.getByRole('checkbox', { name: 'key' });
    expect(keyCheckbox).toBeChecked();
    const queryCheckbox = screen.getByRole('checkbox', { name: 'query' });
    expect(queryCheckbox).not.toBeChecked();
  });

  it('calls onChange when ability card toggled (add)', () => {
    const onChange = vi.fn();
    renderWithMantine(<AbilitiesMatrix selected={['key']} onChange={onChange} />);
    fireEvent.click(screen.getByText('query').closest('[role],label,div,button')?.parentElement ?? screen.getByText('query'));
    expect(onChange).toHaveBeenCalledWith(['key', 'query']);
  });

  it('calls onChange when ability card toggled (remove)', () => {
    const onChange = vi.fn();
    renderWithMantine(<AbilitiesMatrix selected={['key', 'query']} onChange={onChange} />);
    fireEvent.click(screen.getByText('key').closest('[role],label,div,button')?.parentElement ?? screen.getByText('key'));
    expect(onChange).toHaveBeenCalledWith(['query']);
  });
});

describe('FieldTable', () => {
  const fields: Field[] = [
    { name: 'name', type: 'string', required: true },
    { name: 'tier', type: 'enum', required: false },
  ];

  it('renders field rows with current values', () => {
    renderWithMantine(<FieldTable fields={fields} onChange={vi.fn()} />);
    expect(screen.getByDisplayValue('name')).toBeInTheDocument();
    expect(screen.getByDisplayValue('tier')).toBeInTheDocument();
  });

  it('adds a new field row', () => {
    const onChange = vi.fn();
    renderWithMantine(<FieldTable fields={fields} onChange={onChange} />);
    fireEvent.click(screen.getByRole('button', { name: /Add Field/i }));
    expect(onChange).toHaveBeenCalledWith([
      ...fields,
      { name: '', type: 'string', required: false },
    ]);
  });

  it('removes a field row', () => {
    const onChange = vi.fn();
    renderWithMantine(<FieldTable fields={fields} onChange={onChange} />);
    const removeButtons = screen.getAllByRole('button', { name: /Remove/ });
    fireEvent.click(removeButtons[0]);
    expect(onChange).toHaveBeenCalledWith([fields[1]]);
  });

  it('updates field name on input change', () => {
    const onChange = vi.fn();
    renderWithMantine(<FieldTable fields={fields} onChange={onChange} />);
    const nameInput = screen.getByDisplayValue('name');
    fireEvent.change(nameInput, { target: { value: 'fullName' } });
    expect(onChange).toHaveBeenCalledWith([
      { ...fields[0], name: 'fullName' },
      fields[1],
    ]);
  });
});
