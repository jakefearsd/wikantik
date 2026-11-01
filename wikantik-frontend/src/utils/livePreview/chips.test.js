import { describe, it, expect } from 'vitest';
import { chipFor } from './chips';

describe('chipFor', () => {
  it.each([
    ['[{ALLOW view Admin,Editors}]', 'view: Admin, Editors', 'cm-lp-chip cm-lp-chip-acl'],
    ['[{allow edit  Bob ,  Ann}]', 'edit: Bob, Ann', 'cm-lp-chip cm-lp-chip-acl'],
    ['[{DENY edit Guest}]', 'deny edit: Guest', 'cm-lp-chip cm-lp-chip-acl'],
    ['[{$username}]', '$username', 'cm-lp-chip cm-lp-chip-var'],
    ["[{TableOfContents title='x'}]", '⚙ TableOfContents', 'cm-lp-chip cm-lp-chip-plugin'],
    ['[{INSERT com.foo.Bar a=1}]', '⚙ Bar', 'cm-lp-chip cm-lp-chip-plugin'],
    ['[{Bar}]()', '⚙ Bar', 'cm-lp-chip cm-lp-chip-plugin'],
    ["[{SET name='v'}]", "SET name='v'", 'cm-lp-chip'],
  ])('%s', (src, text, cls) => {
    expect(chipFor(src)).toEqual({ text, cls });
  });

  it('truncates long fallback text to 40 chars plus an ellipsis', () => {
    const inner = `SET ${'x'.repeat(60)}`;
    const { text } = chipFor(`[{${inner}}]`);
    expect(text).toBe(`${inner.slice(0, 40)}…`);
  });
});
