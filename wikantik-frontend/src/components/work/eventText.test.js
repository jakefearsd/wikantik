import { describe, it, expect } from 'vitest';
import { eventText, eventSentence, actorLabel } from './eventText';

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
    expect(t.text).toBe('reassigned this to Carol C (was Bob B)');
    expect(t.text).not.toContain('login');
  });
  it('assigned to or from nobody', () => {
    expect(eventText(ev('assigned', { fromValue: null, toValue: 'c', toValueName: 'Carol C' })).text).toBe('assigned this to Carol C');
    expect(eventText(ev('assigned', { fromValue: 'c', fromValueName: 'Carol C', toValue: null })).text).toBe('unassigned this (was Carol C)');
  });
  it('assigned with an unresolved name does not fall back to the login', () => {
    const t = eventText(ev('assigned', { fromValue: 'zed-login', toValue: null }));
    expect(t.text).toBe('unassigned this (was someone)');
  });
  it('assignedToQueueText: a queue is named by its name', () => {
    const t = eventText(ev('assigned', { fromValue: 'bob-login', fromValueName: 'Bob B', toValue: 'queue:reviewer', toValueName: 'Reviewer' }));
    expect(t.text).toBe('reassigned this to Queue · Reviewer (was Bob B)');
  });
  it('assigned from a queue to a person', () => {
    const t = eventText(ev('assigned', { fromValue: 'queue:reviewer', fromValueName: 'Reviewer', toValue: 'c', toValueName: 'Carol C' }));
    expect(t.text).toBe('reassigned this to Carol C (was Queue · Reviewer)');
  });
  it('a queue or agent with no resolved name gets neutral words, never the raw value', () => {
    expect(eventText(ev('assigned', { toValue: 'queue:q9' })).text).toBe('assigned this to a queue');
    expect(eventText(ev('released', { fromValue: 'agent:17', toValue: 'ready', note: 'moved' })).text).not.toContain('agent:17');
    expect(eventText(ev('released', { fromValue: 'agent:17', toValue: 'ready', note: 'moved' })).text).toContain('an agent');
  });
  it('handoff shows the target, the status change and the note', () => {
    const t = eventText(ev('handoff', {
      fromValue: 'in_progress|bob-login', toValue: 'review|carol-login', fromValueName: 'Bob B', toValueName: 'Carol C', note: 'Over to you',
    }));
    expect(t.text).toBe('handed this to Carol C: in progress → review');
    expect(t.note).toBe('Over to you');
    expect(t.prominentNote).toBe(true);
  });
  it('handoffToQueueText', () => {
    const t = eventText(ev('handoff', { fromValue: 'in_progress|bob', toValue: 'review|queue:reviewer', toValueName: 'Reviewer', note: 'ready for review' }));
    expect(t.text).toBe('handed this to Queue · Reviewer: in progress → review');
    expect(eventSentence(ev('handoff', { actorName: 'codex@docker2', actorKind: 'agent', fromValue: 'in_progress|bob', toValue: 'review|queue:reviewer', toValueName: 'Reviewer', note: 'ready for review' })))
      .toBe("codex@docker2 handed this to Queue · Reviewer: in progress → review — 'ready for review'");
  });
  it('handoff from an unassigned item', () => {
    const t = eventText(ev('handoff', { fromValue: 'draft|', toValue: 'ready|c', toValueName: 'Carol C', note: 'n' }));
    expect(t.text).toBe('handed this to Carol C: draft → ready');
  });
  it('claimedText: until on another day carries the date', () => {
    const at = new Date(2026, 10, 16, 14, 0).toISOString();
    const until = new Date(2026, 10, 17, 15, 40).toISOString();
    const t = eventText(ev('claimed', { at, actorName: 'codex@docker2', actorKind: 'agent', fromValue: 'ready', toValue: 'ready', note: until }));
    expect(t.text).toBe('claimed this · until Nov 17 15:40');
    expect(t.note).toBeNull();
    expect(eventSentence(ev('claimed', { at, actorName: 'codex@docker2', actorKind: 'agent', fromValue: 'ready', toValue: 'ready', note: until })))
      .toBe('codex@docker2 claimed this · until Nov 17 15:40');
  });
  it('claimed notes a status change', () => {
    const at = new Date(2026, 10, 16, 14, 0).toISOString();
    const until = new Date(2026, 10, 16, 15, 40).toISOString();
    expect(eventText(ev('claimed', { at, fromValue: 'ready', toValue: 'in_progress', note: until })).text)
      .toBe('claimed this (ready → in progress) · until 15:40');
  });
  it('claimRenewedText', () => {
    const at = new Date(2026, 10, 16, 14, 0).toISOString();
    const until = new Date(2026, 10, 16, 17, 40).toISOString();
    expect(eventSentence(ev('claim_renewed', { at, actorName: 'codex@docker2', actorKind: 'agent', toValue: until })))
      .toBe('codex@docker2 renewed its claim · until 17:40');
  });
  it('releasedTextWithNote: moved', () => {
    expect(eventSentence(ev('released', { actorName: 'Jane Doe', actorKind: 'human', fromValue: 'agent:2', fromValueName: 'claude@ws', toValue: 'review', note: 'moved' })))
      .toBe("Jane Doe released claude@ws's claim because the item was moved (now review)");
  });
  it('released: reassigned', () => {
    expect(eventSentence(ev('released', { actorName: 'Jane Doe', fromValue: 'agent:3', fromValueName: 'codex@docker2', toValue: 'ready', note: 'reassigned' })))
      .toBe("Jane Doe released codex@docker2's claim because the item was reassigned (now ready)");
  });
  it('released: access removed', () => {
    expect(eventSentence(ev('released', { actorName: 'Lena Lead', fromValue: 'agent:3', fromValueName: 'codex@docker2', toValue: 'ready', note: 'access removed' })))
      .toBe("Lena Lead released codex@docker2's claim because its access was removed (now ready)");
  });
  it('released: agent retired', () => {
    expect(eventSentence(ev('released', { actorName: 'Admin A', fromValue: 'agent:3', fromValueName: 'codex@docker2', toValue: 'ready', note: 'agent retired' })))
      .toBe("Admin A released codex@docker2's claim because the agent was retired (now ready)");
  });
  it('released: an explicit release by a person carries their note', () => {
    const t = eventText(ev('released', { actorName: 'Jane Doe', actorKind: 'human', fromValue: 'agent:3', fromValueName: 'codex@docker2', toValue: 'ready', note: 'stuck for hours' }));
    expect(t.text).toBe("released codex@docker2's claim (back to ready)");
    expect(t.note).toBe('stuck for hours');
  });
  it('released: the agent releasing its own claim, note "released"', () => {
    const t = eventText(ev('released', { actorName: 'codex@docker2', actorKind: 'agent', agentId: 3, fromValue: 'agent:3', fromValueName: 'codex@docker2', toValue: 'ready', note: 'released' }));
    expect(t.text).toBe('released its claim (back to ready)');
    expect(t.note).toBeNull();
  });
  it('claimExpiredText is a sentence on its own, without the actor in front', () => {
    const t = eventText(ev('claim_expired', { actorName: 'codex@docker2', actorKind: 'agent', fromValue: 'in_progress', toValue: 'ready' }));
    expect(t.text).toBe('claim by codex@docker2 expired — back to ready');
    expect(t.standalone).toBe(true);
    expect(eventSentence(ev('claim_expired', { actorName: 'codex@docker2', actorKind: 'agent', toValue: 'ready' })))
      .toBe('claim by codex@docker2 expired — back to ready');
  });
  it('claim expired with no actor name says an agent', () => {
    expect(eventText(ev('claim_expired', { actorName: null, actorKind: 'agent', toValue: 'ready' })).text)
      .toBe('claim by an agent expired — back to ready');
  });
  it('agent member events name the agent', () => {
    expect(eventText(ev('member', { toValue: 'agent:3:member', toValueName: 'codex@docker2' })).text)
      .toBe('added agent codex@docker2 as member');
    expect(eventText(ev('member', { fromValue: 'agent:3:viewer', fromValueName: 'codex@docker2' })).text)
      .toBe('removed agent codex@docker2 (viewer)');
  });
  it('agentActorShowsAgentName: the actor label is the agent, never the unknown contributor', () => {
    expect(actorLabel({ actorName: 'codex@docker2', actorKind: 'agent' })).toBe('codex@docker2');
    expect(actorLabel({ actorName: null, actorKind: 'agent' })).toBe('An agent');
    expect(actorLabel({ actorName: null })).toBe('Someone');
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
