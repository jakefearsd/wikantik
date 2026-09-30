import { describe, it, expect } from 'vitest';
import { isValidAttachmentName, getExtension, extensionsMatch, pastedImageName, normalizeAttachmentName, uniqueAttachmentName, attachmentMarkup } from './attachmentNameValidator';

describe('isValidAttachmentName', () => {
  it('accepts simple valid names', () => {
    expect(isValidAttachmentName('beach.jpg')).toBe(true);
    expect(isValidAttachmentName('my-photo_01.png')).toBe(true);
  });

  it('accepts max length (40 chars)', () => {
    expect(isValidAttachmentName('a'.repeat(36) + '.jpg')).toBe(true);
  });

  it('rejects too long', () => {
    expect(isValidAttachmentName('a'.repeat(37) + '.jpg')).toBe(false);
  });

  it('rejects spaces', () => {
    expect(isValidAttachmentName('my photo.jpg')).toBe(false);
  });

  it('rejects special characters', () => {
    expect(isValidAttachmentName('photo#1.jpg')).toBe(false);
    expect(isValidAttachmentName('photo@2.jpg')).toBe(false);
  });

  it('rejects no period', () => {
    expect(isValidAttachmentName('noextension')).toBe(false);
  });

  it('rejects multiple periods', () => {
    expect(isValidAttachmentName('my.backup.jpg')).toBe(false);
  });

  it('rejects leading special chars', () => {
    expect(isValidAttachmentName('.hidden.jpg')).toBe(false);
    expect(isValidAttachmentName('_file.jpg')).toBe(false);
    expect(isValidAttachmentName('-file.jpg')).toBe(false);
  });

  it('rejects trailing hyphen/underscore before dot', () => {
    expect(isValidAttachmentName('file-.jpg')).toBe(false);
    expect(isValidAttachmentName('file_.jpg')).toBe(false);
  });

  it('rejects null/empty', () => {
    expect(isValidAttachmentName(null)).toBe(false);
    expect(isValidAttachmentName('')).toBe(false);
  });
});

describe('getExtension', () => {
  it('extracts lowercase extension', () => {
    expect(getExtension('beach.JPG')).toBe('jpg');
    expect(getExtension('file.png')).toBe('png');
  });
});

describe('extensionsMatch', () => {
  it('matches case-insensitively', () => {
    expect(extensionsMatch('photo.JPG', 'beach.jpg')).toBe(true);
  });
  it('rejects mismatched extensions', () => {
    expect(extensionsMatch('photo.jpg', 'beach.png')).toBe(false);
  });
});

describe('pastedImageName', () => {
  const at = new Date(2026, 8, 30, 14, 12, 3);
  it('stamps local time and maps the MIME type', () => {
    expect(pastedImageName('image/png', at)).toBe('pasted-20260930-141203.png');
    expect(pastedImageName('image/jpeg', at)).toBe('pasted-20260930-141203.jpg');
    expect(pastedImageName('image/x-unknown', at)).toBe('pasted-20260930-141203.png');
  });
});

describe('normalizeAttachmentName', () => {
  it.each([
    ['Screen Shot 2026-09-30 at 14.12.03.png', 'Screen-Shot-2026-09-30-at-14-12-03.png'],
    ['report.final.v2.pdf', 'report-final-v2.pdf'],
    ['__weird__name__.txt', 'weird__name.txt'],
    ['café menu.jpg', 'caf-menu.jpg'],
    ['a'.repeat(60) + '.jpeg', 'a'.repeat(35) + '.jpeg'],
  ])('%s → %s', (input, expected) => {
    expect(normalizeAttachmentName(input)).toBe(expected);
    expect(isValidAttachmentName(expected)).toBe(true);
  });
  it('falls back to "file" when the stem is empty', () => {
    expect(normalizeAttachmentName('###.png')).toBe('file.png');
  });
  it('returns null without a usable extension', () => {
    expect(normalizeAttachmentName('README')).toBeNull();
    expect(normalizeAttachmentName('.bashrc')).toBeNull();
    expect(normalizeAttachmentName('x.')).toBeNull();
  });
});

describe('uniqueAttachmentName', () => {
  it('suffixes the stem on a case-insensitive collision', () => {
    expect(uniqueAttachmentName('a.png', ['A.png', 'a-2.png'])).toBe('a-3.png');
    expect(uniqueAttachmentName('b.png', ['a.png'])).toBe('b.png');
  });
  it('stays within 40 chars', () => {
    const long = 'x'.repeat(36) + '.png';
    const out = uniqueAttachmentName(long, [long]);
    expect(out.length).toBeLessThanOrEqual(40);
    expect(isValidAttachmentName(out)).toBe(true);
    expect(out.endsWith('-2.png')).toBe(true);
  });
});

describe('attachmentMarkup', () => {
  it('matches the attachment-row drag format', () => {
    expect(attachmentMarkup('diagram.png', true)).toBe('![diagram](diagram.png)');
    expect(attachmentMarkup('notes.pdf', false)).toBe('[notes](notes.pdf)');
  });
});
