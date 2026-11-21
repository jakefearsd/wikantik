import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import WorkLabels from './WorkLabels';

const type = (text) => {
  const input = screen.getByLabelText('Add a label');
  fireEvent.change(input, { target: { value: text } });
  fireEvent.keyDown(input, { key: 'Enter' });
  return input;
};

describe('WorkLabels', () => {
  it('shows a chip per label', () => {
    render(<WorkLabels labels={['api', 'ui']} />);
    expect(screen.getByText('api')).toBeInTheDocument();
    expect(screen.getByText('ui')).toBeInTheDocument();
  });
  it('with max 3 shows three chips and +2', () => {
    const { container } = render(<WorkLabels labels={['a', 'b', 'c', 'd', 'e']} max={3} />);
    expect(container.querySelectorAll('.work-label')).toHaveLength(3);
    expect(container.querySelector('.work-label-more').textContent).toBe('+2');
  });
  it('typing an unknown label and Enter adds it', () => {
    const onChange = vi.fn();
    render(<WorkLabels labels={['ui']} editable onChange={onChange} />);
    const input = type('api');
    expect(onChange).toHaveBeenCalledWith(['api', 'ui']);
    expect(input.value).toBe('');
  });
  it('upper case is lower-cased', () => {
    const onChange = vi.fn();
    render(<WorkLabels labels={[]} editable onChange={onChange} />);
    type('  UI ');
    expect(onChange).toHaveBeenCalledWith(['ui']);
  });
  it('a label with a space shows the rule and adds nothing', () => {
    const onChange = vi.fn();
    render(<WorkLabels labels={[]} editable onChange={onChange} />);
    type('two words');
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByRole('alert').textContent).toBe('Use lowercase letters, digits, dots, dashes or underscores, up to 40.');
  });
  it('an eleventh label is refused', () => {
    const onChange = vi.fn();
    const ten = Array.from({ length: 10 }, (_, i) => `l${i}`);
    render(<WorkLabels labels={ten} editable onChange={onChange} />);
    type('extra');
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByRole('alert').textContent).toBe('At most 10 labels.');
  });
  it('a label already present adds nothing', () => {
    const onChange = vi.fn();
    render(<WorkLabels labels={['ui']} editable onChange={onChange} />);
    type('ui');
    expect(onChange).not.toHaveBeenCalled();
  });
  it('remove calls onChange without it', () => {
    const onChange = vi.fn();
    render(<WorkLabels labels={['api', 'ui']} editable onChange={onChange} />);
    fireEvent.click(screen.getByLabelText('Remove label api'));
    expect(onChange).toHaveBeenCalledWith(['ui']);
  });
  it('Enter does not submit the form', () => {
    const submit = vi.fn((e) => e.preventDefault());
    render(<form onSubmit={submit}><WorkLabels labels={[]} editable onChange={() => {}} /></form>);
    type('ui');
    expect(submit).not.toHaveBeenCalled();
  });

  it('two editors on one page get distinct suggestion lists', () => {
    const { container } = render(<><WorkLabels editable labels={[]} suggestions={['a']} /><WorkLabels editable labels={[]} suggestions={['b']} /></>);
    const ids = [...container.querySelectorAll('datalist')].map((d) => d.id);
    expect(new Set(ids).size).toBe(2);
    container.querySelectorAll('input').forEach((input, i) => expect(input.getAttribute('list')).toBe(ids[i]));
  });
});
