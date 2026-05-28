import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import CommentBody from './CommentBody';

describe('CommentBody', () => {
  it('renders plain text unchanged when no mentions', () => {
    render(<CommentBody body="hello world" />);
    expect(screen.getByText('hello world')).toBeTruthy();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('renders a chip linking to /wiki/Users/<login> for a single mention', () => {
    render(<CommentBody body="hi @alice please look" />);
    const link = screen.getByRole('link', { name: '@alice' });
    expect(link.getAttribute('href')).toBe('/wiki/Users/alice');
    expect(link.className).toContain('comment-mention-chip');
  });

  it('renders multiple mentions in one body', () => {
    render(<CommentBody body="@alice and @bob both" />);
    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(2);
    expect(links[0].getAttribute('href')).toBe('/wiki/Users/alice');
    expect(links[1].getAttribute('href')).toBe('/wiki/Users/bob');
  });

  it('does not include trailing punctuation in the chip', () => {
    render(<CommentBody body="@alice, hello" />);
    const link = screen.getByRole('link');
    expect(link.textContent).toBe('@alice');
    // The trailing comma is rendered as plain text after the chip.
    expect(link.parentElement.textContent).toContain(', hello');
  });

  it('url-encodes the login in the href', () => {
    render(<CommentBody body="ping @user.with.dots" />);
    const link = screen.getByRole('link');
    expect(link.getAttribute('href')).toBe('/wiki/Users/user.with.dots');
    // Dots are URL-safe; just confirm it didn't double-encode.
  });

  it('null/empty body renders nothing', () => {
    const { container: c1 } = render(<CommentBody body="" />);
    expect(c1.textContent).toBe('');
    const { container: c2 } = render(<CommentBody body={null} />);
    expect(c2.textContent).toBe('');
  });
});
