import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import ProvenanceBadge from './ProvenanceBadge';

describe('ProvenanceBadge', () => {
  it.each([
    ['human-authored', /page frontmatter|authored.*human|directly/i],
    ['ai-inferred',    /llm.*proposed|extractor|not.*reviewed|hasn't been reviewed/i],
    ['ai-reviewed',    /judge.*approved|llm judge/i],
    ['human-curated',  /admin.*confirmed|curated.*human|explicitly/i],
  ])('renders %s with an explanatory tooltip', (value, expected) => {
    render(<ProvenanceBadge value={value} />);
    const badge = screen.getByText(value);
    expect(badge.getAttribute('title')).toBeTruthy();
    expect(badge.getAttribute('title')).toMatch(expected);
  });

  it('renders unknown values without crashing and without a tooltip', () => {
    render(<ProvenanceBadge value="weird-future-state" />);
    const badge = screen.getByText('weird-future-state');
    expect(badge.getAttribute('title')).toBeNull();
  });
});
