import { describe, it, expect } from 'vitest';
import {
  DISPLAY_NAME_MAX,
  DISPLAY_NAME_MIN,
  displayNameChanged,
  displayNameError,
  isDisplayNameRejection,
} from './displayName';

describe('displayNameError', () => {
  it('accepts an ordinary name and the exact length bounds', () => {
    expect(displayNameError('Test User')).toBeNull();
    expect(displayNameError('Al')).toBeNull();
    expect(displayNameError('a'.repeat(DISPLAY_NAME_MAX))).toBeNull();
  });

  it('requires a non-blank name', () => {
    expect(displayNameError('')).toBe('Display name is required');
    expect(displayNameError('   ')).toBe('Display name is required');
    expect(displayNameError(null)).toBe('Display name is required');
    expect(displayNameError(undefined)).toBe('Display name is required');
  });

  it('measures length after trimming', () => {
    expect(displayNameError(' A ')).toBe(`Display name must be at least ${DISPLAY_NAME_MIN} characters`);
    expect(displayNameError(`  ${'a'.repeat(DISPLAY_NAME_MAX)}  `)).toBeNull();
    expect(displayNameError('a'.repeat(DISPLAY_NAME_MAX + 1)))
      .toBe(`Display name must be ${DISPLAY_NAME_MAX} characters or fewer`);
  });

  it("rejects '@', so an email address cannot be a display name", () => {
    expect(displayNameError('someone@example.com')).toBe("Display name cannot contain '@'");
    expect(displayNameError('Jo @ Home')).toBe("Display name cannot contain '@'");
  });

  it('rejects control characters inside the name', () => {
    expect(displayNameError('Jo\u0000hn')).toBe('Display name cannot contain control characters');
    expect(displayNameError('Jo\u0085hn')).toBe('Display name cannot contain control characters');
    expect(displayNameError('Jo\u007Fhn')).toBe('Display name cannot contain control characters');
  });

  it('reports the length rule before the character rules', () => {
    expect(displayNameError('@')).toBe(`Display name must be at least ${DISPLAY_NAME_MIN} characters`);
  });
});

describe('displayNameChanged', () => {
  it('compares trimmed values and treats absent as empty', () => {
    expect(displayNameChanged('Test User', ' Test User ')).toBe(false);
    expect(displayNameChanged('Test User', 'Test  User')).toBe(true);
    expect(displayNameChanged(undefined, '')).toBe(false);
    expect(displayNameChanged(null, 'New')).toBe(true);
  });
});

describe('isDisplayNameRejection', () => {
  it('attributes every 409 to the display name', () => {
    expect(isDisplayNameRejection({ status: 409 }, false)).toBe(true);
  });

  it('attributes a 400 only when the name was the only change', () => {
    expect(isDisplayNameRejection({ status: 400 }, true)).toBe(true);
    expect(isDisplayNameRejection({ status: 400 }, false)).toBe(false);
  });

  it('never attributes other statuses or a missing error', () => {
    expect(isDisplayNameRejection({ status: 500 }, true)).toBe(false);
    expect(isDisplayNameRejection({ status: 403 }, true)).toBe(false);
    expect(isDisplayNameRejection(undefined, true)).toBe(false);
  });
});
