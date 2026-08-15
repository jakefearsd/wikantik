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

  it('shortens the visible kg_include label but keeps the full accessible name', () => {
    render(<FieldWidget spec={{ key: 'kg_include', label: 'Include in Knowledge Graph', widget: 'TRISTATE' }}
      value={undefined} onChange={noop} />);
    // dense visible label
    expect(screen.getByText('Include in KG')).toBeInTheDocument();
    // control's accessible name stays the full schema label (screen readers, getByLabelText)
    expect(screen.getByLabelText('Include in Knowledge Graph')).toBeTruthy();
  });

  it('wraps the control and its violations in a .fm-control element', () => {
    const { container } = render(
      <FieldWidget
        spec={{ key: 'summary', label: 'Summary', widget: 'TEXT', minLen: 50, maxLen: 160 }}
        value="too short"
        onChange={noop}
        violations={[{ field: 'summary', severity: 'WARNING', code: 'x', message: 'too short msg' }]}
      />,
    );
    const field = container.querySelector('[data-field="summary"]');
    const control = field.querySelector('.fm-control');
    expect(control).toBeTruthy();
    // both the input and the violation text live inside the wrapper, not the label column
    expect(control.querySelector('input')).toBeTruthy();
    expect(control.textContent).toContain('too short msg');
  });

  // ClusterDeclarationDesign Phase 5 — cluster: is either a scalar string or a YAML list
  // (first entry primary). The server-authoritative widget stays TEXT; we key off
  // spec.key === 'cluster' to give it a lossless multi-value control instead of the
  // plain text input (which would collapse an array to a comma-joined string on touch).
  describe('cluster field (scalar-or-list round-trip)', () => {
    const clusterSpec = { key: 'cluster', label: 'Cluster', widget: 'TEXT' };

    it('renders a list-valued cluster as separate entries, not one comma-joined string', () => {
      render(<FieldWidget spec={clusterSpec} value={['machine-learning', 'quantitative-finance']} onChange={noop} />);
      expect(screen.getByText('machine-learning', { exact: false })).toBeInTheDocument();
      expect(screen.getByText('quantitative-finance')).toBeInTheDocument();
      expect(screen.queryByDisplayValue('machine-learning,quantitative-finance')).toBeNull();
    });

    it('emits an ARRAY when a third entry is added to a two-value cluster', () => {
      const onChange = vi.fn();
      render(
        <FieldWidget spec={clusterSpec} value={['machine-learning', 'quantitative-finance']} onChange={onChange} />,
      );
      const input = screen.getByLabelText('Cluster');
      fireEvent.change(input, { target: { value: 'wealth-management' } });
      fireEvent.keyDown(input, { key: 'Enter' });
      expect(onChange).toHaveBeenCalledWith(['machine-learning', 'quantitative-finance', 'wealth-management']);
    });

    it('collapses back to a plain SCALAR string when a two-value cluster drops to one', () => {
      const onChange = vi.fn();
      render(
        <FieldWidget spec={clusterSpec} value={['machine-learning', 'quantitative-finance']} onChange={onChange} />,
      );
      const removeButtons = screen.getAllByRole('button', { name: /remove/i });
      fireEvent.click(removeButtons[1]); // remove 'quantitative-finance'
      expect(onChange).toHaveBeenCalledWith('machine-learning');
    });

    it('emits an ARRAY with the original value FIRST when a second value is added to a scalar cluster', () => {
      const onChange = vi.fn();
      render(<FieldWidget spec={clusterSpec} value="machine-learning" onChange={onChange} />);
      const input = screen.getByLabelText('Cluster');
      fireEvent.change(input, { target: { value: 'quantitative-finance' } });
      fireEvent.keyDown(input, { key: 'Enter' });
      expect(onChange).toHaveBeenCalledWith(['machine-learning', 'quantitative-finance']);
    });

    it('a single-value cluster still round-trips as a plain scalar (untouched)', () => {
      render(<FieldWidget spec={clusterSpec} value="machine-learning" onChange={noop} />);
      expect(screen.getByText('machine-learning')).toBeInTheDocument();
    });

    it('emits empty string for an empty cluster, same as the plain text field does today', () => {
      const onChange = vi.fn();
      render(<FieldWidget spec={clusterSpec} value={undefined} onChange={onChange} />);
      const input = screen.getByLabelText('Cluster');
      fireEvent.change(input, { target: { value: 'solo-cluster' } });
      fireEvent.keyDown(input, { key: 'Enter' });
      expect(onChange).toHaveBeenCalledWith('solo-cluster');
    });
  });
});
