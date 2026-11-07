import { describe, it, expect } from 'vitest';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createMetaState } from './collabMeta.js';

const FIXTURE = resolve(process.cwd(), 'src/collab/__fixtures__/collab-meta-cases.json');

/**
 * The step vocabulary is language-neutral (the Java side replays the same file):
 *  edit {field,value} | add {field,item} | remove {field,item} | take | fail | ack {metaSeq} | remote {ops,metaSeq}
 * `take` marks every unsent edit in flight; `fail` returns the in-flight ones to unsent (a lost ack or refused push);
 * `ack` confirms the edits taken last. Each step records the display `view` and the `pending` count afterwards.
 */
const INPUTS = [
  { name: 'tag add survives the gap between ack and pull', meta: { tags: ['a'] }, metaSeq: 3, steps: [
    { do: 'add', field: 'tags', item: 'x' }, { do: 'take' }, { do: 'ack', metaSeq: 4 },
    { do: 'remote', ops: [], metaSeq: 3 }, { do: 'remote', ops: [{ field: 'tags', value: ['a', 'x'] }], metaSeq: 4 } ] },
  { name: 'lost ack: the op arrives by pull, the resend is a no-op', meta: { tags: [] }, metaSeq: 0, steps: [
    { do: 'add', field: 'tags', item: 'x' }, { do: 'take' }, { do: 'fail' },
    { do: 'remote', ops: [{ field: 'tags', value: ['x'] }], metaSeq: 1 }, { do: 'take' }, { do: 'ack', metaSeq: 1 } ] },
  { name: 'a remote op after mine wins', meta: { status: 'draft' }, metaSeq: 0, steps: [
    { do: 'edit', field: 'status', value: 'active' }, { do: 'take' }, { do: 'ack', metaSeq: 1 },
    { do: 'remote', ops: [{ field: 'status', value: 'active' }, { field: 'status', value: 'archived' }], metaSeq: 2 } ] },
  { name: 'a remote op before mine is overlaid', meta: { status: 'draft' }, metaSeq: 0, steps: [
    { do: 'edit', field: 'status', value: 'active' }, { do: 'take' }, { do: 'ack', metaSeq: 2 },
    { do: 'remote', ops: [{ field: 'status', value: 'archived' }], metaSeq: 1 },
    { do: 'remote', ops: [{ field: 'status', value: 'active' }], metaSeq: 2 } ] },
  { name: 'removing the last item leaves an empty list; a scalar counts as one item', meta: { tags: 'solo' }, metaSeq: 0, steps: [
    { do: 'remove', field: 'tags', item: 'solo' }, { do: 'add', field: 'related', item: 'P' }, { do: 'add', field: 'tags', item: 'n' } ] },
  { name: 'unsent edits of one field coalesce; null removes a field', meta: { summary: 's' }, metaSeq: 0, steps: [
    { do: 'edit', field: 'summary', value: 'a' }, { do: 'edit', field: 'summary', value: 'ab' }, { do: 'edit', field: 'summary', value: null } ] },
  { name: 'concurrent adds from two participants both survive', meta: { tags: ['a'] }, metaSeq: 0, steps: [
    { do: 'add', field: 'tags', item: 'mine' }, { do: 'take' },
    { do: 'remote', ops: [{ field: 'tags', value: ['a', 'theirs'], by: 'Raj' }], metaSeq: 1 },
    { do: 'ack', metaSeq: 2 }, { do: 'remote', ops: [{ field: 'tags', value: ['a', 'theirs', 'mine'] }], metaSeq: 2 } ] },
];

function run(c) {
  const m = createMetaState({ meta: c.meta, metaSeq: c.metaSeq });
  let taken = [];
  return c.steps.map((s) => {
    switch (s.do) {
      case 'edit': m.edit(s.field, s.value); break;
      case 'add': m.add(s.field, s.item); break;
      case 'remove': m.remove(s.field, s.item); break;
      case 'take': taken = m.takeSendable(); break;
      case 'fail': m.failed(); break;
      case 'ack': m.acked(taken, s.metaSeq); break;
      case 'remote': m.applyRemote(s.ops, s.metaSeq); break;
      default: throw new Error(`unknown step ${s.do}`);
    }
    return { ...s, view: m.view(), pending: m.pending().length };
  });
}
const derive = () => INPUTS.map((c) => ({ ...c, steps: run(c) }));

if (process.env.UPDATE_FIXTURES) writeFileSync(FIXTURE, `${JSON.stringify(derive(), null, 2)}\n`);

describe('collab-meta-cases fixture', () => {
  it('is exactly what the implemented rule derives (UPDATE_FIXTURES=1 rewrites it)', () => {
    expect(JSON.parse(readFileSync(FIXTURE, 'utf8'))).toEqual(derive());
  });

  const cases = JSON.parse(readFileSync(FIXTURE, 'utf8'));
  it.each(cases.map((c) => [c.name, c]))('replays: %s', (_n, c) => {
    const again = run({ ...c, steps: c.steps.map(({ view: _v, pending: _p, ...step }) => step) });
    c.steps.forEach((s, i) => {
      expect(again[i].view, `step ${i} (${s.do}) view`).toEqual(s.view);
      expect(again[i].pending, `step ${i} (${s.do}) pending`).toBe(s.pending);
    });
  });

  it('pins the headline values by hand', () => {
    const byName = Object.fromEntries(cases.map((c) => [c.name, c]));
    const gap = byName['tag add survives the gap between ack and pull'].steps;
    expect(gap.map((s) => s.view.tags)).toEqual(Array(5).fill(['a', 'x']));
    expect(gap.map((s) => s.pending)).toEqual([1, 1, 1, 1, 0]);
    const wins = byName['a remote op after mine wins'].steps;
    expect(wins.at(-1).view.status).toBe('archived');
    const lost = byName['lost ack: the op arrives by pull, the resend is a no-op'].steps;
    expect(lost.map((s) => s.view.tags)).toEqual(Array(6).fill(['x']).map((v, i) => (i < 3 ? ['x'] : v)));
    expect(lost.map((s) => s.pending)).toEqual([1, 1, 1, 1, 1, 0]);
    const conc = byName['concurrent adds from two participants both survive'].steps;
    expect(conc[2].view.tags).toEqual(['a', 'theirs', 'mine']);
    expect(conc.at(-1).view.tags).toEqual(['a', 'theirs', 'mine']);
    expect(conc.at(-1).pending).toBe(0);
    const before = byName['a remote op before mine is overlaid'].steps;
    expect(before.map((s) => s.view.status)).toEqual(['active', 'active', 'active', 'active', 'active']);
    expect(before.map((s) => s.pending)).toEqual([1, 1, 1, 1, 0]);
    const last = byName['removing the last item leaves an empty list; a scalar counts as one item'].steps;
    expect(last[0].view.tags).toEqual([]);
  });
});
