// Tabs.test.jsx
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import Tabs from './Tabs';

const TABS = [{ id: 'a', label: 'Alpha' }, { id: 'b', label: 'Beta' }];

describe('Tabs', () => {
  it('marks the active tab and renders the panel', () => {
    render(<Tabs tabs={TABS} active="a" onChange={() => {}}><div>panel-body</div></Tabs>);
    expect(screen.getByRole('tab', { name: 'Alpha' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tab', { name: 'Beta' }).getAttribute('aria-selected')).toBe('false');
    expect(screen.getByText('panel-body')).toBeInTheDocument();
  });

  it('calls onChange with the clicked tab id', () => {
    const onChange = vi.fn();
    render(<Tabs tabs={TABS} active="a" onChange={onChange}>x</Tabs>);
    fireEvent.click(screen.getByRole('tab', { name: 'Beta' }));
    expect(onChange).toHaveBeenCalledWith('b');
  });
});
