import { describe, it, expect } from 'vitest';
import { EditorState, EditorSelection } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import { fullyParsed } from '../test/fullParse';
import { linearCost, LINEAR_COST_TIMEOUT_MS } from '../test/linearCost';
import { editorMarkdownConfig } from './editorMarkdown';
import { formatStateAt, EMPTY_FORMAT_STATE } from './formatState';

/** `doc` with `|` marking the caret, or `[`..`]` marking a selection (markers are removed from the text). */
function stateOf(marked) {
  let doc;
  let selection;
  const caret = marked.indexOf('|');
  if (caret >= 0) {
    doc = marked.replace('|', '');
    selection = EditorSelection.cursor(caret);
  } else {
    const from = marked.indexOf('[');
    const to = marked.indexOf(']') - 1;
    doc = marked.replace('[', '').replace(']', '');
    selection = EditorSelection.range(from, to);
  }
  return fullyParsed(EditorState.create({ doc, selection, extensions: [markdown(editorMarkdownConfig)] }));
}
const at = (marked) => formatStateAt(stateOf(marked));

describe('formatStateAt', () => {
  it('is empty in plain text', () => {
    expect(at('plain te|xt')).toEqual(EMPTY_FORMAT_STATE);
    expect(EMPTY_FORMAT_STATE).toEqual({
      bold: false, italic: false, strike: false, highlight: false, code: false, heading: 0, list: null, quote: false, link: false,
    });
  });

  it('reads inline marks', () => {
    expect(at('**a|b**').bold).toBe(true);
    expect(at('*a|b*').italic).toBe(true);
    expect(at('~~a|b~~').strike).toBe(true);
    expect(at('==a|b==').highlight).toBe(true);
    expect(at('`a|b`').code).toBe(true);
    expect(at('[a|b](http://x)').link).toBe(true);
    expect(at('[[Wi|ki]]').link).toBe(true);
    expect(at('***a|b***')).toMatchObject({ bold: true, italic: true });
  });

  it('reads headings, ATX and setext', () => {
    expect(at('### t|').heading).toBe(3);
    expect(at('Ti|tle\n=====').heading).toBe(1);
    expect(at('Ti|tle\n-----').heading).toBe(2);
  });

  it('reads lists, the innermost winning', () => {
    expect(at('- a|').list).toBe('bullet');
    expect(at('1. a|').list).toBe('ordered');
    expect(at('- [ ] x|').list).toBe('task');
    expect(at('- [x] x|').list).toBe('task');
    expect(at('- a\n  1. b|').list).toBe('ordered');
    expect(at('1. a\n   - b|').list).toBe('bullet');
    expect(at('- a\n  1. b\n\n- c|').list).toBe('bullet');
  });

  it('combines a quote with what is inside it', () => {
    expect(at('> ## t|')).toMatchObject({ quote: true, heading: 2 });
    expect(at('> - a|')).toMatchObject({ quote: true, list: 'bullet' });
  });

  it('with a selection, needs the whole of it inside one node', () => {
    expect(at('a**[b]**c').bold).toBe(true);
    expect(at('a**[b]c**').bold).toBe(true);
    expect(at('a[**b**]c').bold).toBe(true);
    expect(at('[a**b**]c').bold).toBe(false);
    expect(at('a**[b**c]').bold).toBe(false);
    expect(at('a**b**[c d]')).toEqual(EMPTY_FORMAT_STATE);
  });

  it('is linear in document size', () => {
    const { ratio, bound } = linearCost(
      (n) => {
        const doc = '**bold** text\n'.repeat(n);
        return fullyParsed(EditorState.create({
          doc, selection: EditorSelection.cursor(doc.length - 3), extensions: [markdown(editorMarkdownConfig)],
        }), 30000);
      },
      (state) => { for (let i = 0; i < 200; i += 1) formatStateAt(state); },
      { small: 5000 },
    );
    expect(ratio).toBeLessThan(bound);
  }, LINEAR_COST_TIMEOUT_MS);
});
