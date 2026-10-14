import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import CommentBody from './CommentBody';

describe('CommentBody', () => {
  it('renders plain text unchanged when no mentions', () => {
    render(<CommentBody body="hello world" />);
    expect(screen.getByText('hello world')).toBeTruthy();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('renders a mention token as a chip showing the current name from mentionNames', () => {
    const { container } = render(
      <CommentBody body="hi @[Alice A](user:u-1) please look" mentionNames={{ 'u-1': 'Alice A' }} />
    );
    const chip = container.querySelector('.comment-mention-chip');
    expect(chip.textContent).toBe('@Alice A');
    expect(chip.getAttribute('data-user-id')).toBe('u-1');
    expect(container.querySelector('.comment-body').textContent).toBe('hi @Alice A please look');
  });

  it('shows the CURRENT display name from the mentions map, not the snapshot', () => {
    const { container } = render(
      <CommentBody body="ping @[Old Name](user:u-1)" mentionNames={{ 'u-1': 'New Name' }} />
    );
    expect(container.querySelector('.comment-mention-chip').textContent).toBe('@New Name');
    expect(container.textContent).not.toContain('Old Name');
  });

  it('renders a token with no current name as plain @snapshot text, not a chip', () => {
    const { container } = render(<CommentBody body="hi @[Alice A](user:u-1) please look" />);
    expect(container.querySelector('.comment-mention-chip')).toBeNull();
    expect(container.querySelector('.comment-body').textContent).toBe('hi @Alice A please look');
  });

  it('renders a uid missing from the map as plain @snapshot text', () => {
    const { container } = render(
      <CommentBody body="@[Gone Away](user:u-9)" mentionNames={{ 'u-1': 'Someone Else' }} />
    );
    expect(container.querySelector('.comment-mention-chip')).toBeNull();
    expect(container.textContent).toBe('@Gone Away');
  });

  it('renders several chips in one body', () => {
    const { container } = render(
      <CommentBody body="@[A](user:1) and @[B](user:2) both" mentionNames={{ 1: 'A', 2: 'B' }} />
    );
    const chips = container.querySelectorAll('.comment-mention-chip');
    expect(chips).toHaveLength(2);
    expect(chips[0].textContent).toBe('@A');
    expect(chips[1].textContent).toBe('@B');
  });

  it.each(['constructor', 'toString', 'valueOf', 'hasOwnProperty'])(
    'renders a forged user:%s token as plain @snapshot text, not an empty chip',
    (uid) => {
      const { container } = render(<CommentBody body={`hi @[Mallory](user:${uid})`} mentionNames={{}} />);
      expect(container.querySelector('.comment-mention-chip')).toBeNull();
      expect(container.textContent).toBe('hi @Mallory');
    },
  );

  it('never renders a chip as a link', () => {
    render(<CommentBody body="@[Alice A](user:u-1)" mentionNames={{ 'u-1': 'Alice A' }} />);
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('renders a legacy @login as plain text without the dead /wiki/Users link', () => {
    const { container } = render(<CommentBody body="hi @alice please look" />);
    expect(screen.queryByRole('link')).toBeNull();
    expect(container.querySelector('.comment-mention-chip')).toBeNull();
    expect(container.querySelector('.comment-body').textContent).toBe('hi @alice please look');
  });

  it('leaves a malformed token as text', () => {
    const { container } = render(<CommentBody body="see @[Alice](user:) there" />);
    expect(container.querySelector('.comment-mention-chip')).toBeNull();
    expect(container.textContent).toBe('see @[Alice](user:) there');
  });

  it('null/empty body renders nothing', () => {
    const { container: c1 } = render(<CommentBody body="" />);
    expect(c1.textContent).toBe('');
    const { container: c2 } = render(<CommentBody body={null} />);
    expect(c2.textContent).toBe('');
  });
});
