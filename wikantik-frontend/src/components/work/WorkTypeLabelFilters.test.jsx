import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import WorkTypeLabelFilters from './WorkTypeLabelFilters';

const LABELS = [{ name: 'ui', count: 3 }, { name: 'api', count: 1 }];

describe('WorkTypeLabelFilters', () => {
  it('lists the labels with their counts', () => {
    render(<WorkTypeLabelFilters type="" label="" labels={LABELS} onType={vi.fn()} onLabel={vi.fn()} />);
    const select = screen.getByLabelText('Filter by label');
    expect(within(select).getByRole('option', { name: 'All labels' })).toBeInTheDocument();
    expect(within(select).getByRole('option', { name: 'ui (3)' })).toBeInTheDocument();
    expect(within(select).getByRole('option', { name: 'api (1)' })).toBeInTheDocument();
  });

  it('choosing a type calls onType', () => {
    const onType = vi.fn();
    render(<WorkTypeLabelFilters type="" label="" labels={[]} onType={onType} onLabel={vi.fn()} />);
    expect(within(screen.getByLabelText('Filter by type')).getByRole('option', { name: 'All types' })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Filter by type'), { target: { value: 'bug' } });
    expect(onType).toHaveBeenCalledWith('bug');
  });

  it('choosing a label calls onLabel', () => {
    const onLabel = vi.fn();
    render(<WorkTypeLabelFilters type="" label="" labels={LABELS} onType={vi.fn()} onLabel={onLabel} />);
    fireEvent.change(screen.getByLabelText('Filter by label'), { target: { value: 'ui' } });
    expect(onLabel).toHaveBeenCalledWith('ui');
  });
});
