import { describe, it, expect } from 'vitest';
import { normalizePageName, pageHref, pageEditHref } from './pageUrl.js';

describe('normalizePageName', () => {
  it('returns the name unchanged for a plain name', () => {
    expect(normalizePageName('HomePage')).toBe('HomePage');
  });

  it('strips a trailing .md extension', () => {
    expect(normalizePageName('HomePage.md')).toBe('HomePage');
  });

  it('leaves an interior .md alone', () => {
    expect(normalizePageName('Notes.mdFile')).toBe('Notes.mdFile');
  });

  it('returns an empty string for empty input', () => {
    expect(normalizePageName('')).toBe('');
  });

  it('returns an empty string for null', () => {
    expect(normalizePageName(null)).toBe('');
  });

  it('returns an empty string for undefined', () => {
    expect(normalizePageName(undefined)).toBe('');
  });
});

describe('pageHref', () => {
  it('prefixes a plain page name with /wiki/', () => {
    expect(pageHref('HomePage')).toBe('/wiki/HomePage');
  });

  it('percent-encodes spaces as %20 (matches backend encoding)', () => {
    expect(pageHref('Main Page')).toBe('/wiki/Main%20Page');
  });

  it('percent-encodes special characters', () => {
    expect(pageHref('A & B')).toBe('/wiki/A%20%26%20B');
  });

  it('strips a trailing .md before encoding', () => {
    expect(pageHref('HomePage.md')).toBe('/wiki/HomePage');
  });

  it('returns an empty string for empty input', () => {
    expect(pageHref('')).toBe('');
  });

  it('returns an empty string for null', () => {
    expect(pageHref(null)).toBe('');
  });

  it('returns an empty string for undefined', () => {
    expect(pageHref(undefined)).toBe('');
  });
});

describe('pageEditHref', () => {
  it('prefixes a plain page name with /edit/', () => {
    expect(pageEditHref('HomePage')).toBe('/edit/HomePage');
  });

  it('percent-encodes spaces as %20', () => {
    expect(pageEditHref('Main Page')).toBe('/edit/Main%20Page');
  });

  it('strips a trailing .md before encoding', () => {
    expect(pageEditHref('HomePage.md')).toBe('/edit/HomePage');
  });

  it('returns an empty string for empty input', () => {
    expect(pageEditHref('')).toBe('');
  });

  it('returns an empty string for null', () => {
    expect(pageEditHref(null)).toBe('');
  });
});
