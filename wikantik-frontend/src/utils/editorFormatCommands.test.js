import { describe, it, expect } from 'vitest';
import {
  toggleWrap, toggleLinePrefix, insertLink, insertTable, insertCodeBlock, setHeading, insertCallout,
  insertMathBlock, insertRule, insertFence, insertFootnote, insertEmbed, insertAccessRule,
} from './markdownFormat';
import { FORMAT_COMMANDS, MARKERS, FENCE_STARTERS, resolveFormatCommand } from './editorFormatCommands';

/** The switch PageEditor's applyFormat used before the table, kept verbatim as the characterisation oracle. */
function legacy(command, state, liveActive, arg) {
  if (command.startsWith('callout:')) return insertCallout(state, command.slice('callout:'.length));
  if (command.startsWith('fence:')) {
    const language = command.slice('fence:'.length);
    return insertFence(state, language, FENCE_STARTERS[language] ?? '');
  }
  switch (command) {
    case 'bold': return toggleWrap(state, '**');
    case 'italic': return toggleWrap(state, '*');
    case 'code': return toggleWrap(state, '`');
    case 'highlight': return toggleWrap(state, '==');
    case 'strike': return toggleWrap(state, '~~');
    case 'comment': return toggleWrap(state, '%%');
    case 'codeblock': return liveActive ? insertFence(state, '', state.text.slice(state.selStart, state.selEnd)) : insertCodeBlock(state);
    case 'heading': return toggleLinePrefix(state, '## ');
    case 'list': return toggleLinePrefix(state, '- ');
    case 'olist': return toggleLinePrefix(state, '1. ');
    case 'tasklist': return toggleLinePrefix(state, '- [ ] ');
    case 'quote': return toggleLinePrefix(state, '> ');
    case 'table': return insertTable(state);
    case 'link': return insertLink(state);
    case 'h1': return setHeading(state, 1);
    case 'h2': return setHeading(state, 2);
    case 'h3': return setHeading(state, 3);
    case 'h4': return setHeading(state, 4);
    case 'mathblock': return insertMathBlock(state);
    case 'rule': return insertRule(state);
    case 'footnote': return insertFootnote(state);
    case 'embed': return insertEmbed(state);
    case 'accessrule': return insertAccessRule(state, arg);
    default: return undefined;
  }
}

const TEXT = 'first line\nsecond line here\nthird';
const STATES = {
  caret: { text: TEXT, selStart: 14, selEnd: 14 },
  selection: { text: TEXT, selStart: 11, selEnd: 17 },
  empty: { text: '', selStart: 0, selEnd: 0 },
};
const ARG = { permission: 'view', principals: ['Admin', 'Editors'] };
const IDS = [...Object.keys(FORMAT_COMMANDS), 'callout:note', 'callout:warning', 'fence:mermaid', 'fence:plantuml', 'fence:java'];

describe('editorFormatCommands', () => {
  it('covers the whole switch', () => {
    expect(Object.keys(FORMAT_COMMANDS).sort()).toEqual([
      'accessrule', 'bold', 'code', 'codeblock', 'comment', 'embed', 'footnote', 'h1', 'h2', 'h3', 'h4', 'heading',
      'highlight', 'italic', 'link', 'list', 'mathblock', 'olist', 'quote', 'rule', 'strike', 'table', 'tasklist',
    ]);
  });

  for (const id of IDS) {
    for (const [label, state] of Object.entries(STATES)) {
      for (const liveActive of [false, true]) {
        it(`${id} with ${label} (liveActive=${liveActive}) matches the original switch`, () => {
          const run = resolveFormatCommand(id);
          expect(run).toBeTypeOf('function');
          const got = run({ ...state }, { liveActive, arg: ARG });
          expect(got).toEqual(legacy(id, { ...state }, liveActive, ARG));
          expect(got).toHaveProperty('text');
          expect(got).toHaveProperty('selStart');
          expect(got).toHaveProperty('selEnd');
        });
      }
    }
  }

  it('codeblock differs by live mode, and accessrule takes its argument', () => {
    const state = STATES.selection;
    const rich = FORMAT_COMMANDS.codeblock(state, { liveActive: true });
    const source = FORMAT_COMMANDS.codeblock(state, { liveActive: false });
    expect(rich).not.toEqual(source);
    expect(FORMAT_COMMANDS.accessrule(state, { arg: ARG }).text).toContain('ALLOW view Admin, Editors');
  });

  it('answers undefined for an unknown id, including inherited object keys', () => {
    expect(resolveFormatCommand('nope')).toBeUndefined();
    expect(resolveFormatCommand('toString')).toBeUndefined();
    expect(resolveFormatCommand('constructor')).toBeUndefined();
  });

  it('exposes the Obsidian markers', () => {
    expect(MARKERS).toEqual({ highlight: '==', strike: '~~', comment: '%%' });
  });
});
