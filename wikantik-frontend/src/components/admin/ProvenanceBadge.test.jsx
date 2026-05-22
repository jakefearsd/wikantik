import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import ProvenanceBadge from './ProvenanceBadge';

describe('ProvenanceBadge', () => {
  // ── Original case (keep) ────────────────────────────────────────────────
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

  // ── Label text (one assertion per variant) ──────────────────────────────
  it('renders the exact label text for human-authored', () => {
    render(<ProvenanceBadge value="human-authored" />);
    expect(screen.getByText('human-authored')).toBeInTheDocument();
  });

  it('renders the exact label text for human-curated', () => {
    render(<ProvenanceBadge value="human-curated" />);
    expect(screen.getByText('human-curated')).toBeInTheDocument();
  });

  it('renders the exact label text for ai-reviewed', () => {
    render(<ProvenanceBadge value="ai-reviewed" />);
    expect(screen.getByText('ai-reviewed')).toBeInTheDocument();
  });

  it('renders the exact label text for ai-inferred', () => {
    render(<ProvenanceBadge value="ai-inferred" />);
    expect(screen.getByText('ai-inferred')).toBeInTheDocument();
  });

  // ── Inline style colors per variant ────────────────────────────────────
  // happy-dom preserves inline-style hex values as-is rather than converting
  // to rgb(), so we match the hex strings the component writes directly.

  // human-authored: green tones (#e8f5e9 bg, #2e7d32 text)
  it('applies green background color for human-authored', () => {
    render(<ProvenanceBadge value="human-authored" />);
    const badge = screen.getByText('human-authored');
    expect(badge.style.backgroundColor).toBe('#e8f5e9');
    expect(badge.style.color).toBe('#2e7d32');
  });

  // human-curated: darker green (#c8e6c9 bg, #1b5e20 text) — highest trust tier
  it('applies darker green background color for human-curated', () => {
    render(<ProvenanceBadge value="human-curated" />);
    const badge = screen.getByText('human-curated');
    expect(badge.style.backgroundColor).toBe('#c8e6c9');
    expect(badge.style.color).toBe('#1b5e20');
  });

  // ai-reviewed: blue tones (#e3f2fd bg, #1565c0 text)
  it('applies blue background color for ai-reviewed', () => {
    render(<ProvenanceBadge value="ai-reviewed" />);
    const badge = screen.getByText('ai-reviewed');
    expect(badge.style.backgroundColor).toBe('#e3f2fd');
    expect(badge.style.color).toBe('#1565c0');
  });

  // ai-inferred: orange/amber tones (#fff3e0 bg, #e65100 text) — least trusted
  it('applies amber background color for ai-inferred', () => {
    render(<ProvenanceBadge value="ai-inferred" />);
    const badge = screen.getByText('ai-inferred');
    expect(badge.style.backgroundColor).toBe('#fff3e0');
    expect(badge.style.color).toBe('#e65100');
  });

  // unknown values fall back to neutral grey (#f5f5f5 bg, #616161 text)
  it('applies neutral grey fallback colors for unknown provenance values', () => {
    render(<ProvenanceBadge value="unknown-state" />);
    const badge = screen.getByText('unknown-state');
    expect(badge.style.backgroundColor).toBe('#f5f5f5');
    expect(badge.style.color).toBe('#616161');
  });

  // ── Pill / inline-block shape ───────────────────────────────────────────
  it('is rendered as an inline-block element', () => {
    render(<ProvenanceBadge value="ai-inferred" />);
    const badge = screen.getByText('ai-inferred');
    expect(badge.style.display).toBe('inline-block');
  });

  it('has a non-zero border-radius to give the pill shape', () => {
    render(<ProvenanceBadge value="ai-inferred" />);
    const badge = screen.getByText('ai-inferred');
    expect(badge.style.borderRadius).toBeTruthy();
  });
});
