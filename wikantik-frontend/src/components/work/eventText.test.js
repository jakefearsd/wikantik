import { describe, it, expect } from 'vitest';
import { eventText } from './eventText';

const ev = (type, extra = {}) => ({ id: 1, type, fromValue: null, toValue: null, note: null, actorName: 'Ana A', actor: 'ana-login', ...extra });

describe('eventText', () => {
  it('created', () => expect(eventText(ev('created')).text).toBe('created this item'));
  it('edited has no values and asks for a diff link', () => {
    const t = eventText(ev('edited'));
    expect(t.text).toBe('edited the title or text');
    expect(t.hasDiff).toBe(true);
  });
  it('status shows from to', () => expect(eventText(ev('status', { fromValue: 'draft', toValue: 'in_progress' })).text).toBe('status draft → in progress'));
  it('status carries its note', () => expect(eventText(ev('status', { fromValue: 'a', toValue: 'b', note: 'why' })).note).toBe('why'));
  it('assigned uses display names, never logins', () => {
    const t = eventText(ev('assigned', { fromValue: 'bob-login', toValue: 'carol-login', fromValueName: 'Bob B', toValueName: 'Carol C' }));
    expect(t.text).toBe('assigned Bob B → Carol C');
    expect(t.text).not.toContain('login');
  });
  it('assigned to or from nobody', () => {
    expect(eventText(ev('assigned', { fromValue: null, toValue: 'c', toValueName: 'Carol C' })).text).toBe('assigned nobody → Carol C');
    expect(eventText(ev('assigned', { fromValue: 'c', fromValueName: 'Carol C', toValue: null })).text).toBe('assigned Carol C → nobody');
  });
  it('assigned with an unresolved name does not fall back to the login', () => {
    const t = eventText(ev('assigned', { fromValue: 'zed-login', toValue: null }));
    expect(t.text).toBe('assigned someone → nobody');
  });
  it('handoff shows both halves and the note', () => {
    const t = eventText(ev('handoff', {
      fromValue: 'in_progress|bob-login', toValue: 'review|carol-login', fromValueName: 'Bob B', toValueName: 'Carol C', note: 'Over to you',
    }));
    expect(t.text).toBe('handed off: status in progress → review, assignee Bob B → Carol C');
    expect(t.note).toBe('Over to you');
    expect(t.prominentNote).toBe(true);
  });
  it('handoff from an unassigned item', () => {
    const t = eventText(ev('handoff', { fromValue: 'draft|', toValue: 'ready|c', toValueName: 'Carol C', note: 'n' }));
    expect(t.text).toBe('handed off: status draft → ready, assignee nobody → Carol C');
  });
  it('field names the field from the note', () => {
    const t = eventText(ev('field', { note: 'priority', fromValue: '3', toValue: '1' }));
    expect(t.text).toBe('changed priority 3 → 1');
    expect(t.note).toBeNull();
  });
  it('field with an empty side', () => expect(eventText(ev('field', { note: 'due_on', fromValue: null, toValue: '2026-12-01' })).text).toBe('changed due on none → 2026-12-01'));
  it('comment events', () => {
    expect(eventText(ev('commented', { toValue: '5' })).text).toBe('commented');
    expect(eventText(ev('comment_edited', { toValue: '5' })).text).toBe('edited a comment');
    expect(eventText(ev('comment_removed', { toValue: '5' })).text).toBe('removed a comment');
  });
  it('linked and unlinked', () => {
    expect(eventText(ev('linked', { toValue: 'page:Main' })).text).toBe('linked page Main');
    expect(eventText(ev('unlinked', { fromValue: 'url:https://x.test' })).text).toBe('removed link url https://x.test');
  });
  it('member events are phrased with display names, never the principal in the value', () => {
    expect(eventText(ev('member', { toValue: 'user:x-login:member', toValueName: 'Ana A' })).text)
      .toBe('added Ana A as member');
    expect(eventText(ev('member', { fromValue: 'user:x:member', toValue: 'user:x:lead', fromValueName: 'Ana A',
      toValueName: 'Ana A' })).text).toBe("changed Ana A's role member → lead");
    expect(eventText(ev('member', { fromValue: 'user:x:lead', fromValueName: 'Ana A' })).text).toBe('removed Ana A (lead)');
    expect(eventText(ev('member', { toValue: 'group:Devs:viewer', toValueName: 'Devs' })).text).toBe('added group Devs as viewer');
    expect(eventText(ev('member', { toValue: 'user:x-login:member' })).text).toBe('added someone as member');
    expect(eventText(ev('member', { toValue: 'user:x-login:member' })).text).not.toContain('login');
    expect(eventText(ev('member')).text).toBe('changed the members');
  });
  it('settings events say what changed', () => {
    expect(eventText(ev('settings', { note: 'archived', toValue: '2026-11-15T10:00:00Z' })).text).toBe('archived the project');
    expect(eventText(ev('settings', { note: 'name', fromValue: 'Plat', toValue: 'Platform' })).text)
      .toBe('renamed the project Plat → Platform');
    expect(eventText(ev('settings', { note: 'description', fromValue: 'a', toValue: 'b' })).text)
      .toBe('changed the project description');
    expect(eventText(ev('settings', { note: 'default_autonomy', fromValue: 'delegated', toValue: 'supervised' })).text)
      .toBe('changed the default autonomy delegated → supervised');
    expect(eventText(ev('settings')).text).toBe('changed the project settings');
  });
  it('parent and product changes show keys', () => {
    expect(eventText(ev('field', { note: 'parent', fromValue: 'PLAT-3', toValue: 'PLAT-7' })).text)
      .toBe('changed parent PLAT-3 → PLAT-7');
    expect(eventText(ev('field', { note: 'product', fromValue: null, toValue: 'web' })).text)
      .toBe('changed product none → web');
  });
  it('an unknown type renders generically instead of failing', () => {
    expect(eventText(ev('sprint_planned', { fromValue: 'a', toValue: 'b' })).text).toBe('sprint planned: a → b');
    expect(eventText(ev('future_thing')).text).toBe('future thing');
    expect(eventText({}).text).toBe('did something');
  });
});
