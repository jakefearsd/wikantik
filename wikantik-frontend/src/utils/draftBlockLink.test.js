import { describe, it, expect, afterEach } from 'vitest';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { draftBlockLink } from './draftBlockLink';

let views = [];
function viewOf(doc) {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  const view = new EditorView({ parent, state: EditorState.create({ doc }) });
  views.push(view);
  return view;
}
/** A draft call the test answers later: `call.text`, `call.offset`, `call.resolve(body)`, `call.reject(err)`. */
function deferredDraft() {
  const call = {};
  call.draft = (text, offset) => {
    call.text = text;
    call.offset = offset;
    return new Promise((resolve, reject) => { call.resolve = resolve; call.reject = reject; });
  };
  return call;
}
const codeOf = (p) => p.then(() => 'no error', (e) => e.code);

afterEach(() => {
  views.forEach((v) => { v.destroy(); v.dom.parentNode?.remove(); });
  views = [];
});

describe('draftBlockLink', () => {
  it('sends the whole text and the offset, and answers the id with no change for a tagged block', async () => {
    const view = viewOf('Para ^abc\n');
    const call = deferredDraft();
    const p = draftBlockLink(view, 2, call.draft);
    expect([call.text, call.offset]).toEqual(['Para ^abc\n', 2]);
    call.resolve({ id: 'abc', change: null, block: { index: 0, from: 0, to: 9 } });
    expect(await p).toEqual({ id: 'abc', change: null });
  });

  it('maps the change through edits made elsewhere while the request was in flight', async () => {
    const view = viewOf('Alpha.\n\nBeta.\n');
    const call = deferredDraft();
    const p = draftBlockLink(view, 9, call.draft);
    view.dispatch({ changes: { from: 0, insert: 'Intro.\n\n' } });   // before the block: it only moves it
    view.dispatch({ changes: { from: view.state.doc.length, insert: 'Tail.\n' } });
    call.resolve({ id: 'bb', change: { from: 13, to: 13, insert: ' ^bb' }, block: { index: 1, from: 8, to: 13 } });
    expect(await p).toEqual({ id: 'bb', change: { from: 21, to: 21, insert: ' ^bb' } });
    // a second request installs nothing new and maps nothing it did not see
    const again = deferredDraft();
    const q = draftBlockLink(view, 0, again.draft);
    again.resolve({ id: 'i', change: { from: 6, to: 6, insert: ' ^i' }, block: { index: 0, from: 0, to: 6 } });
    expect(await q).toEqual({ id: 'i', change: { from: 6, to: 6, insert: ' ^i' } });
  });

  it('refuses with code "changed" when the block, or the point of the change, was edited meanwhile', async () => {
    for (const edit of [{ from: 10, insert: 'x' }, { from: 13, insert: '!' }, { from: 8, to: 9 }]) {
      const view = viewOf('Alpha.\n\nBeta.\n');
      const call = deferredDraft();
      const p = draftBlockLink(view, 9, call.draft);
      view.dispatch({ changes: edit });
      call.resolve({ id: 'bb', change: { from: 13, to: 13, insert: ' ^bb' }, block: { index: 1, from: 8, to: 13 } });
      expect(await codeOf(p)).toBe('changed');
    }
  });

  it('passes the server refusal on as the code, and any other failure as "failed"', async () => {
    const view = viewOf('# H\n');
    const refused = deferredDraft();
    const p = draftBlockLink(view, 1, refused.draft);
    refused.reject(Object.assign(new Error('409'), { status: 409, body: { reason: 'no-block' } }));
    expect(await codeOf(p)).toBe('no-block');
    const down = deferredDraft();
    const q = draftBlockLink(view, 1, down.draft);
    down.reject(Object.assign(new Error('503'), { status: 503 }));
    const err = await q.catch((e) => e);
    expect(err.code).toBe('failed');
    expect(err.cause.status).toBe(503);
  });
});
