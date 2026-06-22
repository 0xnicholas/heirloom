import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { TypeList } from './TypeList';
import { AbilitiesMatrix } from './AbilitiesMatrix';
import { FieldTable } from './FieldTable';
import type { ResourceType, Field } from '@/lib/types';
import { ABILITIES } from '@/lib/constants';

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
    render(<TypeList types={mockTypes} selected={null} onSelect={vi.fn()} onNew={vi.fn()} />);
    expect(screen.getByText('Customer')).toBeInTheDocument();
    expect(screen.getByText('Order')).toBeInTheDocument();
  });

  it('calls onSelect when type clicked', () => {
    const onSelect = vi.fn();
    render(<TypeList types={mockTypes} selected={null} onSelect={onSelect} onNew={vi.fn()} />);
    fireEvent.click(screen.getByText('Customer'));
    expect(onSelect).toHaveBeenCalledWith('Customer');
  });

  it('filters types by search', () => {
    render(<TypeList types={mockTypes} selected={null} onSelect={vi.fn()} onNew={vi.fn()} />);
    const input = screen.getByPlaceholderText('Search types...');
    fireEvent.change(input, { target: { value: 'Ord' } });
    expect(screen.getByText('Order')).toBeInTheDocument();
    expect(screen.queryByText('Customer')).not.toBeInTheDocument();
  });

  it('shows no types found when filter matches nothing', () => {
    render(<TypeList types={mockTypes} selected={null} onSelect={vi.fn()} onNew={vi.fn()} />);
    const input = screen.getByPlaceholderText('Search types...');
    fireEvent.change(input, { target: { value: 'zzz' } });
    expect(screen.getByText('No types found')).toBeInTheDocument();
  });

  it('calls onNew when + New Type clicked', () => {
    const onNew = vi.fn();
    render(<TypeList types={mockTypes} selected={null} onSelect={vi.fn()} onNew={onNew} />);
    fireEvent.click(screen.getByText('+ New Type'));
    expect(onNew).toHaveBeenCalledOnce();
  });

  it('highlights selected type', () => {
    render(<TypeList types={mockTypes} selected="Customer" onSelect={vi.fn()} onNew={vi.fn()} />);
    const customerBtn = screen.getByText('Customer').closest('button');
    expect(customerBtn?.className).toContain('bg-indigo-50');
  });
});

describe('AbilitiesMatrix', () => {
  it('renders all 8 ability checkboxes', () => {
    const onChange = vi.fn();
    render(<AbilitiesMatrix selected={['key', 'query']} onChange={onChange} />);
    ABILITIES.forEach(a => {
      expect(screen.getByText(a)).toBeInTheDocument();
    });
  });

  it('checked abilities have ✓ marker', () => {
    const onChange = vi.fn();
    render(<AbilitiesMatrix selected={['key']} onChange={onChange} />);
    // key should have ✓
    const keyLabel = screen.getByText('key').closest('label');
    expect(keyLabel?.querySelector('span')?.textContent).toBe('✓');
  });

  it('calls onChange when checkbox toggled', () => {
    const onChange = vi.fn();
    render(<AbilitiesMatrix selected={['key']} onChange={onChange} />);
    fireEvent.click(screen.getByLabelText('query'));
    expect(onChange).toHaveBeenCalledWith(['key', 'query']);
  });

  it('removes ability when already selected', () => {
    const onChange = vi.fn();
    render(<AbilitiesMatrix selected={['key', 'query']} onChange={onChange} />);
    fireEvent.click(screen.getByLabelText('key'));
    expect(onChange).toHaveBeenCalledWith(['query']);
  });
});

describe('FieldTable', () => {
  const fields: Field[] = [
    { name: 'name', type: 'string', required: true },
    { name: 'tier', type: 'enum', required: false },
  ];

  it('renders field rows', () => {
    render(<FieldTable fields={fields} onChange={vi.fn()} />);
    expect(screen.getByDisplayValue('name')).toBeInTheDocument();
    expect(screen.getByDisplayValue('tier')).toBeInTheDocument();
  });

  it('adds a new field row', () => {
    const onChange = vi.fn();
    render(<FieldTable fields={fields} onChange={onChange} />);
    fireEvent.click(screen.getByText('+ Add Field'));
    expect(onChange).toHaveBeenCalledWith([
      ...fields,
      { name: '', type: 'string', required: false },
    ]);
  });

  it('removes a field row', () => {
    const onChange = vi.fn();
    render(<FieldTable fields={fields} onChange={onChange} />);
    const removeButtons = screen.getAllByText('✕');
    fireEvent.click(removeButtons[0]);
    expect(onChange).toHaveBeenCalledWith([fields[1]]);
  });

  it('updates field name on input change', () => {
    const onChange = vi.fn();
    render(<FieldTable fields={fields} onChange={onChange} />);
    const nameInput = screen.getByDisplayValue('name');
    fireEvent.change(nameInput, { target: { value: 'fullName' } });
    expect(onChange).toHaveBeenCalledWith([
      { ...fields[0], name: 'fullName' },
      fields[1],
    ]);
  });
});
