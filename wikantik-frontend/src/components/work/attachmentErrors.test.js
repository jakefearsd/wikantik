import { beforeEach, describe, expect, it } from 'vitest';
import { limitsFor, noteUploaded, rememberLimits, resetLimits, sizeProblem, uploadErrorText } from './attachmentErrors';

const MB = 1024 * 1024;
const lim = { mayAttach: true, maxFileBytes: 20 * MB, maxItemBytes: 30 * MB, usedBytes: 25 * MB };
const file = (mb, name = 'f.bin') => ({ name, size: mb * MB });
const err = (status, reason, message = 'words that must be ignored') => ({ status, body: { reason, message } });

beforeEach(() => resetLimits());

describe('attachmentErrors', () => {
  it('doesNotPreCheckBeforeTheLimitsAreKnown', () => {
    expect(limitsFor('PLAT-1')).toBeNull();
    expect(sizeProblem(file(500), 'PLAT-1')).toBeNull();
  });

  it('usesTheServersLimitsPerItem', () => {
    rememberLimits('PLAT-1', lim);
    expect(sizeProblem(file(12), 'PLAT-2')).toBeNull();
    expect(sizeProblem(file(21), 'PLAT-1')).toBe('f.bin is larger than 20.0 MB, the most one file may be.');
    expect(sizeProblem(file(6), 'PLAT-1')).toBe('f.bin does not fit: this item may hold 30.0 MB of files and 25.0 MB is used, leaving 5.0 MB.');
    expect(sizeProblem(file(5), 'PLAT-1')).toBeNull();
  });

  it('countsWhatThisPageJustUploaded', () => {
    rememberLimits('PLAT-1', lim);
    noteUploaded('PLAT-1', 4 * MB);
    expect(sizeProblem(file(2), 'PLAT-1')).toMatch(/leaving 1\.0 MB/);
  });

  it('mapsEachReasonToItsMessage', () => {
    rememberLimits('PLAT-1', lim);
    expect(uploadErrorText(err(413, 'too_large'), 'a.txt', 'PLAT-1')).toBe('a.txt: too large; one file may be at most 20.0 MB.');
    expect(uploadErrorText(err(413, 'item_full'), 'a.txt', 'PLAT-1')).toBe('a.txt: this item is full; its files may total at most 30.0 MB.');
    expect(uploadErrorText(err(409, 'archived'), 'a.txt')).toBe('a.txt: this project is archived; nothing was attached.');
    expect(uploadErrorText(err(403), 'a.txt')).toBe('a.txt: you are not allowed to attach files here.');
    expect(uploadErrorText(err(404), 'a.txt')).toBe('a.txt: this item is no longer available.');
    expect(uploadErrorText({ message: 'x' }, 'a.txt')).toMatch(/could not reach the server/);
  });

  it('wordsTheReportersOwnShareByReasonNotByTheServersWords', () => {
    expect(uploadErrorText(err(413, 'reporter_full', 'Your files on this request would pass the limit of 10485760 bytes.'), 'a.txt', 'PLAT-1'))
      .toBe('a.txt: you have attached as much to this request as you may; remove one of your files to make room.');
    expect(uploadErrorText(err(413, 'reporter_full'), null, 'PLAT-9'))
      .toBe('you have attached as much to this request as you may; remove one of your files to make room.');
  });

  it('wordsTheLimitsGenericallyWhenTheyAreUnknown', () => {
    expect(uploadErrorText(err(413, 'too_large'), 'a.txt', 'PLAT-9')).toBe('a.txt: too large for one file.');
    expect(uploadErrorText(err(413, 'item_full'), 'a.txt', 'PLAT-9')).toBe('a.txt: this item is full; remove a file to make room.');
  });

  it('aTooLargeWordedLikeAnyOtherTextIsStillKeyedOnReasonOnly', () => {
    expect(uploadErrorText(err(413, 'item_full', 'This file is larger than the limit of 5 bytes.'), null, 'PLAT-9'))
      .toBe('this item is full; remove a file to make room.');
  });
});
