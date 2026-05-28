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
      <MentionPicker open={true} candidates={CANDS} selectedIndex={1} onSelect={() => {}} anchorPos={null} />
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
      <MentionPicker open={true} candidates={CANDS} selectedIndex={0} onSelect={onSelect} anchorPos={null} />
    );
    fireEvent.mouseDown(screen.getAllByRole('option')[1]);
    expect(onSelect).toHaveBeenCalledWith('alicia');
  });
});
