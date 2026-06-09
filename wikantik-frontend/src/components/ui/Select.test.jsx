// Select.test.jsx
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import Select from './Select';

describe('Select', () => {
  it('renders options and fires onChange with the chosen value', () => {
    const onChange = vi.fn();
    render(
      <Select
        value="a"
        ariaLabel="pick"
        options={[{ value: 'a', label: 'A' }, { value: 'b', label: 'B' }]}
        onChange={onChange}
      />,
    );
    fireEvent.change(screen.getByLabelText('pick'), { target: { value: 'b' } });
    expect(onChange).toHaveBeenCalledWith('b');
  });

  it('supports plain string options', () => {
    render(<Select value="" ariaLabel="s" options={['x', 'y']} onChange={() => {}} />);
    expect(screen.getByRole('option', { name: 'x' })).toBeInTheDocument();
  });

  it('renders a placeholder option when given', () => {
    render(<Select value="" ariaLabel="s" placeholder="Choose…" options={['x']} onChange={() => {}} />);
    expect(screen.getByRole('option', { name: 'Choose…' })).toBeInTheDocument();
  });
});
