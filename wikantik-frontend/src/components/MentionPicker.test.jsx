import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MentionPicker from './MentionPicker';

const CANDS = [
  { loginName: 'alice', fullName: 'Alice A' },
  { loginName: 'alicia', fullName: 'Alicia B' },
];

describe('MentionPicker', () => {
  it('renders nothing when closed', () => {
    const { container } = render(
      <MentionPicker open={false} candidates={CANDS} selectedIndex={0} onSelect={() => {}} anchorPos={null} />
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders nothing when there are no candidates', () => {
    const { container } = render(
      <MentionPicker open={true} candidates={[]} selectedIndex={0} onSelect={() => {}} anchorPos={null} />
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders candidates and marks selectedIndex active', () => {
    render(
      <MentionPicker open={true} candidates={CANDS} selectedIndex={1} onSelect={() => {}} anchorPos={{ top: 100, left: 50 }} />
    );
    const items = screen.getAllByRole('option');
    expect(items).toHaveLength(2);
    expect(items[0].className).not.toContain('active');
    expect(items[1].className).toContain('active');
    expect(items[1].getAttribute('aria-selected')).toBe('true');
  });

  it('fires onSelect with loginName when clicked', () => {
    const onSelect = vi.fn();
    render(
      <MentionPicker open={true} candidates={CANDS} selectedIndex={0} onSelect={onSelect} anchorPos={{ top: 100, left: 50 }} />
    );
    fireEvent.mouseDown(screen.getAllByRole('option')[1]);
    expect(onSelect).toHaveBeenCalledWith('alicia');
  });

  it('renders nothing when anchorPos is null (no caret coords yet)', () => {
    const { container } = render(
      <MentionPicker open={true} candidates={[{ loginName: 'a', fullName: '' }]} selectedIndex={0} onSelect={() => {}} anchorPos={null} />
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders at the supplied anchorPos via position: fixed', () => {
    render(
      <MentionPicker open={true} candidates={[{ loginName: 'a', fullName: '' }]} selectedIndex={0} onSelect={() => {}} anchorPos={{ top: 222, left: 333 }} />
    );
    const list = screen.getByRole('listbox');
    expect(list.style.position).toBe('fixed');
    expect(list.style.top).toBe('222px');
    expect(list.style.left).toBe('333px');
  });

  it('moves when anchorPos changes (caret moves)', () => {
    const { rerender } = render(
      <MentionPicker open={true} candidates={[{ loginName: 'a', fullName: '' }]} selectedIndex={0} onSelect={() => {}} anchorPos={{ top: 100, left: 50 }} />
    );
    expect(screen.getByRole('listbox').style.top).toBe('100px');
    rerender(
      <MentionPicker open={true} candidates={[{ loginName: 'a', fullName: '' }]} selectedIndex={0} onSelect={() => {}} anchorPos={{ top: 200, left: 75 }} />
    );
    expect(screen.getByRole('listbox').style.top).toBe('200px');
  });
});
