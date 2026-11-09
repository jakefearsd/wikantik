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
    render(<FieldWidget spec={{ key: 'reviewed', label: 'Reviewed', widget: 'TRISTATE' }} value={undefined} onChange={onChange} />);
    const sel = screen.getByLabelText('Reviewed');
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

  it('renders a STRING_LIST field as a chip list that accepts phrases with spaces', () => {
    const onChange = vi.fn();
    render(<FieldWidget spec={{ key: 'aliases', label: 'Aliases', widget: 'STRING_LIST' }}
      value={['index fund']} onChange={onChange} />);
    expect(screen.getByText('index fund')).toBeInTheDocument();
    const input = screen.getByPlaceholderText('Aliases');
    fireEvent.change(input, { target: { value: 'low cost funds' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onChange).toHaveBeenCalledWith(['index fund', 'low cost funds']);
  });

  it('offers tag suggestions to the TAGS widget', () => {
    const { container } = render(<FieldWidget spec={{ key: 'tags', label: 'Tags', widget: 'TAGS' }}
      value={[]} onChange={() => {}} tagSuggestions={['finance', 'investing']} />);
    const options = [...container.querySelectorAll('datalist option')].map((o) => o.value);
    expect(options).toEqual(['finance', 'investing']);
  });
  describe('co-editing hooks', () => {
    const spec = { key: 'summary', label: 'Summary', widget: 'TEXT' };

    it('reports focus and blur of the field, not moves inside it', () => {
      const onFieldFocus = vi.fn();
      const onFieldBlur = vi.fn();
      render(<FieldWidget spec={spec} value="x" onChange={noop} onFieldFocus={onFieldFocus} onFieldBlur={onFieldBlur} />);
      const input = screen.getByLabelText('Summary');
      fireEvent.focus(input);
      expect(onFieldFocus).toHaveBeenCalledWith('summary');
      fireEvent.blur(input, { relatedTarget: document.body });
      expect(onFieldBlur).toHaveBeenCalledWith('summary');
    });

    it('shows "changed by" when another editor changed the field', () => {
      render(<FieldWidget spec={spec} value="mine" onChange={noop} remoteChanged={{ summary: 'Raj', tags: 'Bo' }} />);
      expect(screen.getByTestId('fm-remote-summary')).toHaveTextContent('changed by Raj');
      expect(screen.getByLabelText('Summary')).toHaveValue('mine');
    });

    it('shows no indicator for another field\'s remote change', () => {
      render(<FieldWidget spec={spec} value="mine" onChange={noop} remoteChanged={{ tags: 'Bo' }} />);
      expect(screen.queryByTestId('fm-remote-summary')).toBeNull();
    });

    it('notes an outside change it kept the user\'s value over', () => {
      render(<FieldWidget spec={spec} value="mine" onChange={noop} externalConflicts={{ summary: 'wiki-agent' }} />);
      expect(screen.getByTestId('fm-conflict-summary')).toHaveTextContent('kept yours; wiki-agent also changed this');
    });
  });
});
