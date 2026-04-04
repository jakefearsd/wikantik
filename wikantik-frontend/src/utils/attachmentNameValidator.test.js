import { describe, it, expect } from 'vitest';
import { isValidAttachmentName, getExtension, extensionsMatch } from './attachmentNameValidator';

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
