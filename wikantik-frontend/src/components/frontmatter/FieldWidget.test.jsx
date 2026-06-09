// FieldWidget.test.jsx
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import FieldWidget from './FieldWidget';

const noop = () => {};

describe('FieldWidget', () => {
  it('renders a READONLY field as plain text', () => {
    render(<FieldWidget spec={{ key: 'canonical_id', label: 'Canonical ID', widget: 'READONLY' }}
      value="01ABC" onChange={noop} />);
    expect(screen.getByTestId('fm-canonical_id').textContent).toBe('01ABC');
  });

  it('maps a TRISTATE select to true/false/undefined', () => {
    const onChange = vi.fn();
    render(<FieldWidget spec={{ key: 'kg_include', label: 'KG', widget: 'TRISTATE' }} value={undefined} onChange={onChange} />);
    const sel = screen.getByLabelText('KG');
    fireEvent.change(sel, { target: { value: 'true' } });
    expect(onChange).toHaveBeenCalledWith(true);
    fireEvent.change(sel, { target: { value: '' } });
    expect(onChange).toHaveBeenCalledWith(undefined);
  });

  it('shows a length counter for a bounded TEXT field', () => {
    render(<FieldWidget spec={{ key: 'summary', label: 'Summary', widget: 'TEXT', minLen: 50, maxLen: 160 }}
      value="too short" onChange={noop} />);
    // 9 chars, under the 50 minimum -> counter shows 9/160 and is flagged
    expect(screen.getByText('9/160')).toBeInTheDocument();
  });

  it('edits runbook block lines into string arrays', () => {
    const onChange = vi.fn();
    render(<FieldWidget spec={{ key: 'runbook', label: 'Runbook', widget: 'RUNBOOK_BLOCK' }} value={{}} onChange={onChange} />);
    fireEvent.change(screen.getByLabelText('runbook steps'), { target: { value: 'step one\nstep two' } });
    expect(onChange).toHaveBeenCalledWith({ steps: ['step one', 'step two'] });
  });
});
