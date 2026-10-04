import { describe, it, expect } from 'vitest';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { ensureSyntaxTree, syntaxTree } from '@codemirror/language';
import { markdown } from '@codemirror/lang-markdown';
import { fullyParsed, parseFully } from './fullParse';

// Long enough that the parse a new state starts with (a ~20 ms budget) stops short on any machine.
const DOC = 'para **x** _y_ ~~z~~\n\n'.repeat(20000);

describe('fullParse', () => {
  it('ensureSyntaxTree alone leaves the state holding its partial tree (the trap)', () => {
    const state = EditorState.create({ doc: DOC, extensions: [markdown()] });
    expect(ensureSyntaxTree(state, DOC.length, 20000).length).toBe(DOC.length);
    expect(syntaxTree(state).length).toBeLessThan(DOC.length);
  });

  it('fullyParsed returns a state whose own tree covers the document', () => {
    const state = fullyParsed(EditorState.create({ doc: DOC, extensions: [markdown()] }), 20000);
    expect(syntaxTree(state).length).toBe(DOC.length);
  });

  it('parseFully brings a view to a state whose own tree covers the document', () => {
    const view = new EditorView({ doc: DOC, extensions: [markdown()] });
    parseFully(view, 20000);
    expect(syntaxTree(view.state).length).toBe(DOC.length);
    view.destroy();
  });
});
