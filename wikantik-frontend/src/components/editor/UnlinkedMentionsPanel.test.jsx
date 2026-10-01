import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import UnlinkedMentionsPanel from './UnlinkedMentionsPanel';

const mention = { target: 'IndexFundsHub', title: 'Index Funds', phrase: 'index fund', from: 3, to: 13, line: 4, context: 'an index fund here' };
const setup = (props = {}) => {
  const handlers = { onLink: vi.fn(), onIgnore: vi.fn(), onJump: vi.fn(), onRetry: vi.fn() };
  render(<UnlinkedMentionsPanel status="ok" mentions={[mention]} {...handlers} {...props} />);
  return handlers;
};

describe('UnlinkedMentionsPanel', () => {
  it('lists the phrase, title, line and context with a count badge', () => {
    setup();
    const row = screen.getByTestId('mention-row');
    expect(row).toHaveTextContent('index fund');
    expect(row).toHaveTextContent('Index Funds');
    expect(screen.getByTestId('mention-line')).toHaveTextContent('L4');
    expect(screen.getByTestId('mention-context')).toHaveAccessibleName('Jump to line 4: an index fund here');
    expect(row).toHaveTextContent('an index fund here');
    expect(screen.getByTestId('mentions-panel')).toHaveTextContent('Unlinked mentions1');
  });
  it('Link and Ignore call their handlers with the mention', () => {
    const h = setup();
    fireEvent.click(screen.getByTestId('mention-link'));
    fireEvent.click(screen.getByTestId('mention-ignore'));
    expect(h.onLink).toHaveBeenCalledWith(mention);
    expect(h.onIgnore).toHaveBeenCalledWith(mention);
  });
  it('keeps the arrow and the title together as one wrapping unit', () => {
    setup();
    const target = screen.getByTestId('mention-target');
    expect(target.textContent).toBe('→\u00a0Index Funds');
    expect(target.parentElement.querySelector('.mention-phrase')).toHaveTextContent('“index fund”');
  });
  it('renders Link and Ignore as real small buttons', () => {
    setup();
    expect(screen.getByTestId('mention-link')).toHaveClass('mention-btn', 'mention-btn-link');
    expect(screen.getByTestId('mention-ignore')).toHaveClass('mention-btn', 'mention-btn-ignore');
  });
  it('clicking the context jumps', () => {
    const h = setup();
    fireEvent.click(screen.getByText(/an index fund here/));
    expect(h.onJump).toHaveBeenCalledWith(mention);
  });
  it('empty ok state', () => {
    setup({ mentions: [] });
    expect(screen.getByText('No unlinked mentions')).toBeInTheDocument();
  });
  it('warming state', () => {
    setup({ status: 'warming', mentions: [] });
    expect(screen.getByText('Mentions available shortly')).toBeInTheDocument();
  });
  it('error state offers retry', () => {
    const h = setup({ status: 'error', mentions: [] });
    expect(screen.getByText(/Couldn't scan for mentions/)).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('mention-retry'));
    expect(h.onRetry).toHaveBeenCalled();
  });
});
