/**
 * The client half of A14 (metadata operations): turns frontmatter edits into wire ops and keeps the display
 * metadata honest while they travel.
 *
 *  - `diffMeta(prev, next)`: whole-object edits (RawYaml, AdvancedKeyValues) become field ops. The set fields
 *    (`tags`, `related`, `aliases`) become `add`/`remove` so concurrent additions survive; any other changed field becomes
 *    a SET, and a removed key a SET null.
 *  - `createMetaState`: the server's metadata plus the edits not yet confirmed, replayed on top for display. A sent
 *    edit leaves the pending list only when a pull has delivered `metaSeq >= its acked metaSeq`, so a pending add cannot
 *    flicker away in the gap between the push ack and the next pull. Pulled ops are resolved `{field, value}`.
 *  - `createMetaQueue`: per-field debounce for keystroke-driven fields; `flush()` sends at once (blur, publish).
 *
 * The rules mirror the server (and the fake): REMOVE drops every equal item (as strings) and of the last item resolves to [], a scalar is a one-item list for
 * ADD/REMOVE, values compare as normalised JSON.
 */
export const SET_FIELDS = ['tags', 'related', 'aliases'];
const isSetField = (f) => SET_FIELDS.includes(f);

const norm = (v) => (v === undefined ? null : JSON.parse(JSON.stringify(v)));
const jsonEq = (a, b) => JSON.stringify(norm(a)) === JSON.stringify(norm(b));
const toList = (v) => (Array.isArray(v) ? v : typeof v === 'string' && v ? [v] : []);
const listOfStrings = (v) => toList(v).every((x) => typeof x === 'string');

export function diffMeta(prev = {}, next = {}) {
  const edits = [];
  const fields = [...new Set([...Object.keys(prev), ...Object.keys(next)])];
  for (const field of fields) {
    const a = prev[field];
    const b = next[field];
    if (jsonEq(a, b)) continue;
    const bothLists = isSetField(field) && listOfStrings(a) && listOfStrings(b) && (b === undefined || b === null || typeof b === 'string' || Array.isArray(b));
    if (bothLists) {
      const before = toList(a);
      const after = toList(b);
      before.filter((x) => !after.includes(x)).forEach((item) => edits.push({ field, remove: item }));
      after.filter((x) => !before.includes(x)).forEach((item) => edits.push({ field, add: item }));
    } else {
      edits.push({ field, value: b === undefined ? null : norm(b) });
    }
  }
  return edits;
}

/** Applies one wire edit (`value` / `add` / `remove`) to `meta` in place, as the server resolves it. */
function applyEdit(meta, edit) {
  const { field } = edit;
  if ('value' in edit) {
    if (edit.value === null || edit.value === undefined) delete meta[field]; else meta[field] = norm(edit.value);
    return;
  }
  const list = [...toList(meta[field])];
  if ('add' in edit) { if (!list.includes(edit.add)) list.push(edit.add); } else {
    // Q6/Q7: REMOVE drops every equal item; set items compare (and are stored) as strings.
    meta[field] = list.filter((x) => String(x) !== edit.remove).map(String);
    return;
  }
  meta[field] = list;
}

export function createMetaState({ meta = {}, metaSeq = 0 } = {}) {
  let server = norm(meta) ?? {};
  let serverSeq = metaSeq;
  /** @type {{edit: object, state: 'unsent'|'inflight'|'acked', ackedSeq: number|null}[]} */
  let entries = [];

  const queue = (edit) => { entries.push({ edit, state: 'unsent', ackedSeq: null }); };

  return {
    /** Replaces a field's value; an unsent SET of the same field is coalesced into this one. */
    edit(field, value) {
      const edit = { field, value: value === undefined ? null : norm(value) };
      const i = entries.findIndex((e) => e.state === 'unsent' && e.edit.field === field && 'value' in e.edit);
      if (i >= 0) entries[i] = { edit, state: 'unsent', ackedSeq: null }; else queue(edit);
    },
    add(field, item) { queue({ field, add: item }); },
    remove(field, item) { queue({ field, remove: item }); },
    /** Queues the ops `diffMeta` finds between two whole-object states. */
    replace(prev, next) { diffMeta(prev, next).forEach(queue); },
    /** The edits to put on the next push (marked in flight); give the same objects back to `acked` or `failed`. */
    takeSendable() {
      const out = [];
      for (const e of entries) if (e.state === 'unsent') { e.state = 'inflight'; out.push(e.edit); }
      return out;
    },
    /** The push failed (stale-base, network, 429): the in-flight edits go out again; edits are idempotent. */
    failed(edits) {
      for (const e of entries) if (e.state === 'inflight' && (!edits || edits.includes(e.edit))) e.state = 'unsent';
    },
    acked(edits, seq) {
      // a reply without a numeric metaSeq cannot be waited on: treat it as the seq already reached (retire at once)
      const at = typeof seq === 'number' ? seq : serverSeq;
      for (const e of entries) if (edits.includes(e.edit) && e.state !== 'unsent') { e.state = 'acked'; e.ackedSeq = at; }
      // a pull may already have reached this seq (it overtook the ack): nothing more to wait for
      entries = entries.filter((e) => !(e.state === 'acked' && e.ackedSeq <= serverSeq));
    },
    /** A pull's resolved `{field, value}` ops, in server order, and the `metaSeq` the pull reached. */
    applyRemote(ops, seq) {
      for (const op of ops ?? []) applyEdit(server, op);
      if (typeof seq === 'number') serverSeq = Math.max(serverSeq, seq);
      entries = entries.filter((e) => !(e.state === 'acked' && e.ackedSeq <= serverSeq));
    },
    /** The display metadata: the server's, with every unconfirmed edit replayed on top. */
    view() {
      const out = norm(server);
      for (const e of entries) applyEdit(out, e.edit);
      return out;
    },
    pending: () => entries.map((e) => e.edit),
    /**
     * The edits the server has not confirmed (unsent or in flight) that would still change something: what a recovery
     * would have to show. Replayed in order over the server's metadata, so an in-flight edit a pull already delivered
     * (ahead of its ack) is left out, while an edit undoing an earlier unconfirmed one is kept. Deliberately
     * conservative: within one pull the server may have applied my value and then overwritten it, so an edit the server
     * did apply can still be listed. Showing one edit too many in a recovery panel is safe; hiding a lost one is not.
     */
    unconfirmed() {
      let running = norm(server);
      const out = [];
      for (const e of entries) {
        const next = norm(running);
        applyEdit(next, e.edit);
        if (e.state !== 'acked' && diffMeta(running, next).length > 0) out.push(e.edit);
        running = next;
      }
      return out;
    },
    /** Forgets every local edit (eviction for lost edit rights, A13): the view is the server's metadata again. */
    discardPending() { entries = []; },
    serverSeq: () => serverSeq,
  };
}

/** Per-field debounce in front of `onSend(edits)`. `schedule(fn, ms)` returns a cancel function. */
export function createMetaQueue({
  debounceMs = 400,
  onSend = () => {},
  schedule = (fn, ms) => { const id = setTimeout(fn, ms); return () => clearTimeout(id); },
} = {}) {
  const waiting = new Map(); // field -> { edit, cancel }
  let disposed = false;
  function send(edits) { if (edits.length) onSend(edits); }
  return {
    /** A keystroke-driven SET: the latest value per field goes out once typing pauses. */
    edit(field, value) {
      if (disposed) return;
      waiting.get(field)?.cancel();
      const edit = { field, value: value === undefined ? null : value };
      const cancel = schedule(() => { waiting.delete(field); send([edit]); }, debounceMs);
      waiting.set(field, { edit, cancel });
    },
    /** Discrete ops (set adds/removes, diffs) need no debounce. */
    enqueue(edits) { send(edits); },
    flush() {
      const edits = [...waiting.values()].map((w) => { w.cancel(); return w.edit; });
      waiting.clear();
      send(edits);
    },
    /** Teardown: flushes what is still inside the debounce window (never drops it), returns it, and goes quiet. */
    dispose() {
      const edits = [...waiting.values()].map((w) => w.edit);
      this.flush();
      disposed = true;
      return edits;
    },
    hasWaiting: () => waiting.size > 0,
  };
}
