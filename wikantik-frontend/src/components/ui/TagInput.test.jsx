// TagInput.test.jsx
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import TagInput from './TagInput';

describe('TagInput', () => {
  it('renders existing tags as chips', () => {
    render(<TagInput value={['one', 'two']} onChange={() => {}} placeholder="Add tag" />);
    expect(screen.getByText('one')).toBeInTheDocument();
    expect(screen.getByText('two')).toBeInTheDocument();
  });

  it('adds a tag on Enter', () => {
    const onChange = vi.fn();
    render(<TagInput value={['one']} onChange={onChange} placeholder="Add tag" />);
    const input = screen.getByLabelText('Add tag');
    fireEvent.change(input, { target: { value: 'two' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onChange).toHaveBeenCalledWith(['one', 'two']);
  });

  it('does not add a duplicate tag', () => {
    const onChange = vi.fn();
    render(<TagInput value={['one']} onChange={onChange} placeholder="Add tag" />);
    const input = screen.getByLabelText('Add tag');
    fireEvent.change(input, { target: { value: 'one' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onChange).not.toHaveBeenCalled();
  });

  it('removes a tag via its chip remove button', () => {
    const onChange = vi.fn();
    render(<TagInput value={['one']} onChange={onChange} placeholder="Add tag" />);
    fireEvent.click(screen.getByRole('button', { name: /remove/i }));
    expect(onChange).toHaveBeenCalledWith([]);
  });
});
