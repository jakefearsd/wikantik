import { describe, it, expect } from 'vitest';
import { titleToSlug, isValidSlug } from './slugUtils.js';

describe('titleToSlug', () => {
  it('converts a normal title to CamelCase', () => {
    expect(titleToSlug('AI Model Training in 2026')).toBe('AIModelTrainingIn2026');
  });

  it('capitalises all-lowercase words', () => {
    expect(titleToSlug('retirement planning in spain')).toBe('RetirementPlanningInSpain');
  });

  it('handles titles containing digits', () => {
    expect(titleToSlug('Top 10 Tips for 2025')).toBe('Top10TipsFor2025');
  });

  it('replaces & with And', () => {
    expect(titleToSlug('Berlin: History & Culture')).toBe('BerlinHistoryAndCulture');
  });

  it('strips parentheses and question marks', () => {
    expect(titleToSlug('what is a 401(k)?')).toBe('WhatIsA401k');
  });

  it('strips colons from words', () => {
    expect(titleToSlug('Berlin: History')).toBe('BerlinHistory');
  });

  it('strips apostrophes', () => {
    expect(titleToSlug("Don't panic")).toBe('DontPanic');
  });

  it('returns empty string for empty input', () => {
    expect(titleToSlug('')).toBe('');
  });

  it('handles already-CamelCase input unchanged (except joining)', () => {
    expect(titleToSlug('CamelCase Title')).toBe('CamelCaseTitle');
  });

  it('trims leading and trailing whitespace', () => {
    expect(titleToSlug('  hello world  ')).toBe('HelloWorld');
  });

  it('splits on hyphens', () => {
    expect(titleToSlug('step-by-step guide')).toBe('StepByStepGuide');
  });

  it('splits on underscores', () => {
    expect(titleToSlug('some_page_name')).toBe('SomePageName');
  });
});

describe('isValidSlug', () => {
  it('returns true for a valid alphanumeric slug', () => {
    expect(isValidSlug('HelloWorld')).toBe(true);
  });

  it('returns true for a slug with digits', () => {
    expect(isValidSlug('Page2025')).toBe(true);
  });

  it('returns false for an empty string', () => {
    expect(isValidSlug('')).toBe(false);
  });

  it('returns false for null', () => {
    expect(isValidSlug(null)).toBe(false);
  });

  it('returns false for undefined', () => {
    expect(isValidSlug(undefined)).toBe(false);
  });

  it('returns false for a string of only whitespace', () => {
    expect(isValidSlug('   ')).toBe(false);
  });

  it('returns false for a slug that is too long (>100 chars)', () => {
    expect(isValidSlug('A'.repeat(101))).toBe(false);
  });

  it('returns true for a slug of exactly 100 chars', () => {
    expect(isValidSlug('A'.repeat(100))).toBe(true);
  });

  it('returns false for a slug containing spaces', () => {
    expect(isValidSlug('Hello World')).toBe(false);
  });

  it('returns false for a slug containing hyphens', () => {
    expect(isValidSlug('hello-world')).toBe(false);
  });

  it('returns false for a slug containing special characters', () => {
    expect(isValidSlug('hello!')).toBe(false);
  });
});
