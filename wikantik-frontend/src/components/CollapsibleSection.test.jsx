import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import CollapsibleSection from './CollapsibleSection';

beforeEach(() => localStorage.clear());

describe('CollapsibleSection', () => {
  it('renders the title and toggles aria-expanded', () => {
    render(
      <CollapsibleSection id="t" title="My pages" defaultOpen>
        <a href="/x">child</a>
      </CollapsibleSection>,
    );
    const btn = screen.getByRole('button', { name: /my pages/i });
    expect(btn).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('child')).toBeInTheDocument();
    fireEvent.click(btn);
    expect(btn).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('child')).not.toBeInTheDocument();
  });

  it('persists collapsed state to localStorage', () => {
    const { unmount } = render(
      <CollapsibleSection id="persist" title="Sec" defaultOpen>
        <span>body</span>
      </CollapsibleSection>,
    );
    fireEvent.click(screen.getByRole('button', { name: /sec/i }));
    unmount();
    render(
      <CollapsibleSection id="persist" title="Sec" defaultOpen>
        <span>body</span>
      </CollapsibleSection>,
    );
    expect(screen.getByRole('button', { name: /sec/i })).toHaveAttribute('aria-expanded', 'false');
  });
});
