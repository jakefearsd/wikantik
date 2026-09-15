// Combobox.test.jsx
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import Combobox from './Combobox';

describe('Combobox', () => {
  it('resyncs the displayed query when the value prop changes externally', () => {
    const { rerender } = render(
      <Combobox value="interval-trees" options={[]} onChange={() => {}} />,
    );
    const input = screen.getByRole('combobox');
    expect(input.value).toBe('interval-trees');

    rerender(<Combobox value="graph-theory" options={[]} onChange={() => {}} />);
    expect(input.value).toBe('graph-theory');
  });

  it('keeps in-progress typing when the value prop is unchanged across a re-render', () => {
    const { rerender } = render(
      <Combobox value="interval-trees" options={[]} onChange={() => {}} />,
    );
    const input = screen.getByRole('combobox');
    fireEvent.change(input, { target: { value: 'interval-t' } });
    expect(input.value).toBe('interval-t');

    // Same value prop, unrelated re-render — must not clobber the user's typing.
    rerender(<Combobox value="interval-trees" options={[]} onChange={() => {}} />);
    expect(input.value).toBe('interval-t');
  });

  it('shows static options on focus and selects one on click', () => {
    const onChange = vi.fn();
    render(
      <Combobox
        value=""
        options={['interval-trees', 'graph-theory']}
        onChange={onChange}
        placeholder="cluster"
      />,
    );
    const input = screen.getByRole('combobox');
    fireEvent.focus(input);
    fireEvent.mouseDown(screen.getByText('interval-trees'));
    expect(onChange).toHaveBeenCalledWith('interval-trees');
  });

  it('filters static options by the typed query on focus', () => {
    render(
      <Combobox value="graph" options={['interval-trees', 'graph-theory']} onChange={() => {}} />,
    );
    const input = screen.getByRole('combobox');
    fireEvent.focus(input);
    expect(screen.getByText('graph-theory')).toBeInTheDocument();
    expect(screen.queryByText('interval-trees')).toBeNull();
  });

  it('accepts free entry on Enter', () => {
    const onChange = vi.fn();
    render(<Combobox value="" options={[]} onChange={onChange} allowFreeEntry />);
    const input = screen.getByRole('combobox');
    fireEvent.change(input, { target: { value: 'custom-slug' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onChange).toHaveBeenCalledWith('custom-slug');
  });
});
