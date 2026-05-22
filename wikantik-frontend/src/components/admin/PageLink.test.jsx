import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import PageLink from './PageLink';

// PageLink uses plain <a> (not react-router Link) — no MemoryRouter needed.

describe('PageLink', () => {
  it('renders an anchor with the correct view href for a simple name', () => {
    render(<PageLink name="SomePage" />);
    const link = screen.getByRole('link', { name: 'SomePage' });
    expect(link).toBeInTheDocument();
    expect(link.getAttribute('href')).toBe('/wiki/SomePage');
  });

  it('URL-encodes spaces in the page name', () => {
    render(<PageLink name="My Page" />);
    const link = screen.getByRole('link', { name: 'My Page' });
    expect(link.getAttribute('href')).toBe('/wiki/My%20Page');
  });

  it('URL-encodes slashes in the page name', () => {
    render(<PageLink name="Foo/Bar" />);
    const link = screen.getByRole('link', { name: 'Foo/Bar' });
    expect(link.getAttribute('href')).toBe('/wiki/Foo%2FBar');
  });

  it('strips a trailing .md extension from both href and display text', () => {
    render(<PageLink name="SomePage.md" />);
    const link = screen.getByRole('link', { name: 'SomePage' });
    expect(link.getAttribute('href')).toBe('/wiki/SomePage');
  });

  it('opens in a new tab with noopener noreferrer', () => {
    render(<PageLink name="SomePage" />);
    const link = screen.getByRole('link', { name: 'SomePage' });
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
  });

  it('carries a tooltip with the normalized page name', () => {
    render(<PageLink name="SomePage.md" />);
    const link = screen.getByRole('link', { name: 'SomePage' });
    expect(link.getAttribute('title')).toMatch(/SomePage/);
  });

  it('renders custom children as link text instead of the page name', () => {
    render(<PageLink name="SomePage"><em>custom label</em></PageLink>);
    const link = screen.getByRole('link', { name: 'custom label' });
    expect(link.getAttribute('href')).toBe('/wiki/SomePage');
  });

  it('applies a custom className to the anchor', () => {
    render(<PageLink name="SomePage" className="my-class" />);
    const link = screen.getByRole('link', { name: 'SomePage' });
    expect(link.classList.contains('my-class')).toBe(true);
  });

  it('renders a plain span (no anchor) when name is empty string', () => {
    render(<PageLink name="" />);
    expect(screen.queryByRole('link')).toBeNull();
    // The span is in the DOM (empty display text — normalizePageName('') === '')
  });

  it('renders a plain span (no anchor) when name is null/undefined', () => {
    render(<PageLink />);
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('applies className to the fallback span when no href is produced', () => {
    render(<PageLink name="" className="fallback-class" />);
    const span = document.querySelector('span.fallback-class');
    expect(span).not.toBeNull();
  });
});
