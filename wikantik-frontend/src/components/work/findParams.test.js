import { describe, it, expect } from 'vitest';
import { FIELDS, OPEN_STATUSES, BUILT_IN_VIEWS, fromQuery, toQuery, fromSaved, toSaved } from './findParams';

const FULL = {
  projects: ['OPS', 'PLAT'], status: ['ready', 'review'], level: ['task'], type: 'bug', label: ['ui', 'api'],
  priority: ['1', '2'], assignee: 'Ada Lovelace', queue: 'triage', reporter: 'me', product: 'wikantik',
  sprint: 'active', watching: true, q: 'a, b', dueBefore: '2026-12-31', updatedSince: '2026-11-01', sort: 'due',
};

describe('findParams', () => {
  it('round-trips every field through the URL', () => {
    expect(Object.keys(FULL)).toEqual(FIELDS.map((f) => f.name));
    const q = toQuery(FULL);
    expect(fromQuery(new URLSearchParams(q))).toEqual(FULL);
  });

  it('writes a canonical query and leaves out empty fields', () => {
    expect(toQuery({ sort: 'due', label: ['ui', ' ', 'api'], assignee: '', watching: false, status: [] }))
      .toBe('label=ui%2Capi&sort=due');
    expect(toQuery({})).toBe('');
  });

  it('ignores unknown parameters and reads repeats', () => {
    const f = fromQuery(new URLSearchParams('colour=red&limit=5&cursor=x&label=ui&label=api,db&watching=no'));
    expect(f).toEqual({ label: ['ui', 'api', 'db'] });
  });

  it('round-trips a saved filter', () => {
    const saved = toSaved(FULL);
    expect(saved.label).toBe('ui,api');
    expect(saved.watching).toBe('true');
    expect(Object.values(saved).every((v) => typeof v === 'string')).toBe(true);
    expect(fromSaved(saved)).toEqual(FULL);
    expect(fromSaved(null)).toEqual({});
  });

  it('built-in views use me and open statuses', () => {
    expect(OPEN_STATUSES).toEqual(['draft', 'refining', 'ready', 'in_progress', 'review']);
    const by = Object.fromEntries(BUILT_IN_VIEWS.map((v) => [v.id, v]));
    expect(by.assigned.filter.assignee).toBe('me');
    expect(by.reported.filter.reporter).toBe('me');
    expect(by.watching.filter.watching).toBe(true);
    for (const v of BUILT_IN_VIEWS) expect(v.filter.status).toEqual(OPEN_STATUSES);
    expect(toQuery(by.assigned.filter)).toBe('status=draft%2Crefining%2Cready%2Cin_progress%2Creview&assignee=me&sort=updated');
  });
});
