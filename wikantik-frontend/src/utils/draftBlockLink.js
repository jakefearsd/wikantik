// A block link for the editor's unsaved buffer, decided by the server: POST /api/pages/{name}/blocks/draft reads the
// buffer with the reader's own block model (access rules stripped, the reader's parser) and answers the block the reader
// shows at an offset — its id, or the one edit that gives it one. The editor never decides an id from its own parse
// (blockIds.js only draws). While the request is in flight the user may keep typing: every edit is recorded, the
// answer is mapped through them, and it is refused when an edit touched the block or the place of the change.
import { ChangeSet, StateEffect, StateField } from '@codemirror/state';

const track = StateEffect.define();
const untrack = StateEffect.define();
/** Per request in flight: the edits made since it was sent, as one composed ChangeDesc over the text it sent. */
const inFlight = StateField.define({
  create: () => new Map(),
  update(edits, tr) {
    let next = edits;
    if (tr.docChanged && edits.size) next = new Map([...edits].map(([k, d]) => [k, d.composeDesc(tr.changes.desc)]));
    for (const e of tr.effects) {
      if (e.is(track)) next = new Map(next).set(e.value, ChangeSet.empty(tr.state.doc.length).desc);
      else if (e.is(untrack)) { next = new Map(next); next.delete(e.value); }
    }
    return next;
  },
});
let lastKey = 0;

const failure = (code, cause) => Object.assign(new Error(`Block link: ${code}`), { code, cause });

/**
 * Asks `draft(text, offset)` — the server's draft call — for the link to the block at `offset` of `view`'s text.
 *
 * @returns {Promise<{id: string, change: {from: number, to: number, insert: string}|null}>} the id, and the edit to
 *   apply (in the CURRENT text) for a block that has none
 * @throws Error with `code`: the server's refusal (`no-block`, `refused`); `changed` when an edit made meanwhile touched
 *   the block or the place of the change; `failed` for anything else (`cause` holds the request's error)
 */
export async function draftBlockLink(view, offset, draft) {
  if (view.state.field(inFlight, false) === undefined) view.dispatch({ effects: StateEffect.appendConfig.of(inFlight) });
  lastKey += 1;
  const key = lastKey;
  view.dispatch({ effects: track.of(key) });
  let answer = null;
  let failed = null;
  try {
    answer = await draft(view.state.doc.toString(), offset);
  } catch (err) {
    failed = err;
  }
  const edits = view.state.field(inFlight).get(key);
  view.dispatch({ effects: untrack.of(key) });
  if (failed) throw failure((failed?.status === 409 && failed.body?.reason) || 'failed', failed);
  const { id, change, block } = answer;
  const from = Math.min(block.from, change ? change.from : block.from);
  const to = Math.max(block.to, change ? change.to : block.to);
  let touched = false;
  edits.iterChangedRanges((fromA, toA) => { if (fromA <= to && toA >= from) touched = true; });
  if (touched) throw failure('changed');
  return {
    id,
    change: change && { from: edits.mapPos(change.from, -1), to: edits.mapPos(change.to, 1), insert: change.insert },
  };
}
