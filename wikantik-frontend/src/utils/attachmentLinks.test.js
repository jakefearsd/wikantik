import { describe, it, expect } from 'vitest';
import { attachmentLinkChanges, rewriteAttachmentLinks } from './attachmentLinks';

const apply = (text, changes) =>
  [...changes].sort((a, b) => b.from - a.from).reduce((t, c) => t.slice(0, c.from) + c.insert + t.slice(c.to), text);

describe('attachmentLinkChanges', () => {
  it('targets only the link destination of image and plain links to the old name', () => {
    const text = 'See ![alt](old.png) and [link](old.png) here.';
    const changes = attachmentLinkChanges(text, 'old.png', 'new.png');
    expect(changes).toEqual([
      { from: 11, to: 18, insert: 'new.png' },
      { from: 31, to: 38, insert: 'new.png' },
    ]);
    expect(apply(text, changes)).toBe('See ![alt](new.png) and [link](new.png) here.');
  });

  it('leaves bare mentions, other files and look-alike names alone', () => {
    const text = 'old.png [a](old.png.bak) [b](xold.png) [c](oldXpng) ![d](other.png)';
    expect(attachmentLinkChanges(text, 'old.png', 'new.png')).toEqual([]);
  });

  it('treats regex metacharacters in the old name literally', () => {
    const text = '[a](f(1)+.png) [b](f1+.png)';
    const changes = attachmentLinkChanges(text, 'f(1)+.png', 'g.png');
    expect(apply(text, changes)).toBe('[a](g.png) [b](f1+.png)');
  });

  it('inserts the new name verbatim ($ is not a replacement pattern)', () => {
    expect(rewriteAttachmentLinks('[a](old.png)', 'old.png', '$1$&.png')).toBe('[a]($1$&.png)');
  });

  it('returns an empty list for empty text or an empty old name', () => {
    expect(attachmentLinkChanges('', 'old.png', 'new.png')).toEqual([]);
    expect(attachmentLinkChanges('[a]()', '', 'new.png')).toEqual([]);
  });
});

describe('rewriteAttachmentLinks', () => {
  it('rewrites every link to the old name', () => {
    expect(rewriteAttachmentLinks('![x](old.png)\n[y](old.png)\n[z](keep.png)', 'old.png', 'new.png'))
      .toBe('![x](new.png)\n[y](new.png)\n[z](keep.png)');
  });
});
