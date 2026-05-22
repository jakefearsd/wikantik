import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import PageEditLink from './PageEditLink';

// PageEditLink uses plain <a> (not react-router Link) — no MemoryRouter needed.

describe('PageEditLink', () => {
  it('renders an anchor with the correct edit href for a simple name', () => {
    render(<PageEditLink name="SomePage" />);
    const link = screen.getByRole('link', { name: 'SomePage' });
    expect(link).toBeInTheDocument();
    expect(link.getAttribute('href')).toBe('/edit/SomePage');
  });

  it('URL-encodes spaces in the page name', () => {
    render(<PageEditLink name="My Page" />);
    const link = screen.getByRole('link', { name: 'My Page' });
    expect(link.getAttribute('href')).toBe('/edit/My%20Page');
  });

  it('URL-encodes slashes in the page name', () => {
    render(<PageEditLink name="Foo/Bar" />);
    const link = screen.getByRole('link', { name: 'Foo/Bar' });
    expect(link.getAttribute('href')).toBe('/edit/Foo%2FBar');
  });

  it('strips a trailing .md extension from both href and display text', () => {
    render(<PageEditLink name="SomePage.md" />);
    const link = screen.getByRole('link', { name: 'SomePage' });
    expect(link.getAttribute('href')).toBe('/edit/SomePage');
  });

  it('produces an /edit/ prefix, not /wiki/', () => {
    render(<PageEditLink name="SomePage" />);
    const link = screen.getByRole('link', { name: 'SomePage' });
    expect(link.getAttribute('href')).toMatch(/^\/edit\//);
    expect(link.getAttribute('href')).not.toMatch(/^\/wiki\//);
  });

  it('opens in a new tab with noopener noreferrer', () => {
    render(<PageEditLink name="SomePage" />);
    const link = screen.getByRole('link', { name: 'SomePage' });
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
  });

  it('carries a tooltip mentioning "Edit" and the normalized page name', () => {
    render(<PageEditLink name="SomePage.md" />);
    const link = screen.getByRole('link', { name: 'SomePage' });
    const title = link.getAttribute('title');
    expect(title).toMatch(/Edit/i);
    expect(title).toMatch(/SomePage/);
  });

  it('renders custom children as link text instead of the page name', () => {
    render(<PageEditLink name="SomePage"><strong>Edit me</strong></PageEditLink>);
    const link = screen.getByRole('link', { name: 'Edit me' });
    expect(link.getAttribute('href')).toBe('/edit/SomePage');
  });

  it('applies a custom className to the anchor', () => {
    render(<PageEditLink name="SomePage" className="edit-class" />);
    const link = screen.getByRole('link', { name: 'SomePage' });
    expect(link.classList.contains('edit-class')).toBe(true);
  });

  it('renders a plain span (no anchor) when name is empty string', () => {
    render(<PageEditLink name="" />);
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('renders a plain span (no anchor) when name is null/undefined', () => {
    render(<PageEditLink />);
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('applies className to the fallback span when no href is produced', () => {
    render(<PageEditLink name="" className="fallback-class" />);
    const span = document.querySelector('span.fallback-class');
    expect(span).not.toBeNull();
  });
});
