import { describe, it, expect } from 'vitest';
import { filesFromPaste, filesFromDrop, dragCarriesFiles } from './editorFileEvents';

const png = new File(['x'], 'image.png', { type: 'image/png' });
const clip = (text, files) => ({ getData: (t) => (t === 'text/plain' ? text : ''), files });

describe('filesFromPaste', () => {
  it('returns clipboard files when there is no text', () => {
    expect(filesFromPaste(clip('', [png]))).toEqual([png]);
  });
  it('prefers text: rich-text copies also carry a rendered image (Review Focus #1)', () => {
    expect(filesFromPaste(clip('Quarterly summary', [png]))).toEqual([]);
  });
  it('handles a missing clipboard', () => {
    expect(filesFromPaste(null)).toEqual([]);
  });
});

describe('filesFromDrop / dragCarriesFiles', () => {
  it('extracts dropped files', () => {
    expect(filesFromDrop({ files: [png] })).toEqual([png]);
    expect(filesFromDrop(null)).toEqual([]);
  });
  it('detects file drags only', () => {
    expect(dragCarriesFiles({ types: ['Files'] })).toBe(true);
    expect(dragCarriesFiles({ types: ['text/plain'] })).toBe(false);
    expect(dragCarriesFiles(undefined)).toBe(false);
  });
});
