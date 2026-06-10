import { describe, it, expect } from 'vitest';
import { isWideField } from './fieldLayout';

describe('isWideField', () => {
  it('marks textarea/tags/page-refs/runbook widgets wide', () => {
    expect(isWideField({ widget: 'TEXTAREA' })).toBe(true);
    expect(isWideField({ widget: 'TAGS' })).toBe(true);
    expect(isWideField({ widget: 'PAGE_REFS' })).toBe(true);
    expect(isWideField({ widget: 'RUNBOOK_BLOCK' })).toBe(true);
  });
  it('marks a long TEXT field (maxLen >= 80) wide', () => {
    expect(isWideField({ widget: 'TEXT', maxLen: 160 })).toBe(true);
  });
  it('keeps short scalars 2-up', () => {
    expect(isWideField({ widget: 'TEXT', maxLen: 40 })).toBe(false);
    expect(isWideField({ widget: 'ENUM' })).toBe(false);
    expect(isWideField({ widget: 'DATE' })).toBe(false);
  });
});
