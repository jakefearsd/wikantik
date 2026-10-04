/**
 * `ensureSyntaxTree` finishes the parse but only returns the tree: `syntaxTree(state)`, which the code under test
 * reads, keeps the partial tree the state was created with until the next transaction. That first parse gets
 * about 20 ms, so under load it can stop short even on a few lines, and a test that only calls ensureSyntaxTree
 * passes or fails on machine speed. These finish the parse and move it into the state.
 */
import { ensureSyntaxTree, forceParsing } from '@codemirror/language';

/** A state like `state` whose own syntax tree covers the whole document. */
export function fullyParsed(state, timeout = 5000) {
  if (!ensureSyntaxTree(state, state.doc.length, timeout)) {
    throw new Error(`no complete syntax tree within ${timeout} ms (or the state has no language)`);
  }
  return state.update({}).state;
}

/** Brings `view` to a state whose own syntax tree covers the whole document. */
export function parseFully(view, timeout = 5000) {
  if (!forceParsing(view, view.state.doc.length, timeout)) {
    throw new Error(`no complete syntax tree within ${timeout} ms (or the editor has no language)`);
  }
}
