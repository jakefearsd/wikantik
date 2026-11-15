import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import WorkLinks from './WorkLinks';

const LINKS = [
  { id: 1, itemId: 7, kind: 'page', target: 'Main' },
  { id: 2, itemId: 7, kind: 'issue', target: 'https://github.com/acme/repo/issues/4' },
  { id: 3, itemId: 7, kind: 'url', target: 'https://example.test/doc' },
  { id: 4, itemId: 7, kind: 'commit', target: 'abc1234' },
  { id: 5, itemId: 7, kind: 'blocked_by', target: 'PLAT-2' },
  { id: 6, itemId: 7, kind: 'url', target: 'javascript:alert(1)' },
];
const setup = (props = {}) => {
  const onAdd = vi.fn().mockResolvedValue(true);
  const onRemove = vi.fn().mockResolvedValue(true);
  render(<MemoryRouter><WorkLinks links={LINKS} blocked canEdit onAdd={onAdd} onRemove={onRemove} {...props} /></MemoryRouter>);
  return { onAdd, onRemove };
};

describe('WorkLinks', () => {
  it('pageLinkRendersAsWikiLinkByNameOnly', () => {
    setup();
    const a = screen.getByRole('link', { name: 'Main' });
    expect(a).toHaveAttribute('href', '/wiki/Main');
    expect(a).not.toHaveAttribute('target');
  });

  it('opens issue and url links in a new tab with noopener noreferrer, and never a javascript: url', () => {
    setup();
    const issue = screen.getByRole('link', { name: 'https://github.com/acme/repo/issues/4' });
    expect(issue).toHaveAttribute('target', '_blank');
    expect(issue).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByRole('link', { name: 'https://example.test/doc' })).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.queryByRole('link', { name: /javascript:/ })).toBeNull();
    expect(screen.getByText('javascript:alert(1)')).toBeInTheDocument();
  });

  it('shows a commit as text', () => {
    setup();
    expect(screen.getByText('abc1234').tagName).toBe('CODE');
    expect(screen.queryByRole('link', { name: 'abc1234' })).toBeNull();
  });

  it('blockedByShowsTargetStatusOnlyIfVisible: links to the item and shows blocked only from the view flag', () => {
    setup();
    const a = screen.getByRole('link', { name: 'PLAT-2' });
    expect(a).toHaveAttribute('href', '/work/item/PLAT-2');
    expect(within(a.closest('li')).getByText('Blocked')).toBeInTheDocument();
  });

  it('shows no blocked badge when the item is not blocked', () => {
    setup({ blocked: false });
    expect(screen.queryByText('Blocked')).toBeNull();
  });

  it('adds a link of the chosen kind', () => {
    const { onAdd } = setup();
    fireEvent.change(screen.getByLabelText('Link kind'), { target: { value: 'commit' } });
    fireEvent.change(screen.getByLabelText('Link target'), { target: { value: 'deadbeef' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add link' }));
    expect(onAdd).toHaveBeenCalledWith('commit', 'deadbeef');
  });

  it('does not add a blank target', () => {
    const { onAdd } = setup();
    fireEvent.click(screen.getByRole('button', { name: 'Add link' }));
    expect(onAdd).not.toHaveBeenCalled();
  });

  it('removes a link', () => {
    const { onRemove } = setup();
    fireEvent.click(screen.getByRole('button', { name: 'Remove link Main' }));
    expect(onRemove).toHaveBeenCalledWith(1);
  });

  it('viewers get no link controls', () => {
    setup({ canEdit: false });
    expect(screen.queryByRole('button', { name: 'Add link' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Remove link/ })).toBeNull();
    expect(screen.getByRole('link', { name: 'Main' })).toBeInTheDocument();
  });

  it('says when there are no links', () => {
    setup({ links: [], blocked: false, canEdit: false });
    expect(screen.getByText('No links.')).toBeInTheDocument();
  });
});
