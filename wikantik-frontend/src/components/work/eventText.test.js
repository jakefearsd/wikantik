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
  it('released: moved says the actor moved the item, ending the claim', () => {
    expect(eventSentence(ev('released', { actorName: 'Jane Doe', actorKind: 'human', fromValue: 'agent:2', fromValueName: 'claude@ws', toValue: 'review', note: 'moved' })))
      .toBe("Jane Doe moved this item, ending claude@ws's claim (now review)");
  });
  it('released: reassigned', () => {
    expect(eventSentence(ev('released', { actorName: 'Jane Doe', fromValue: 'agent:3', fromValueName: 'codex@docker2', toValue: 'ready', note: 'reassigned' })))
      .toBe("Jane Doe reassigned this item, ending codex@docker2's claim (now ready)");
  });
  it('released: access removed names what the actor removed', () => {
    expect(eventSentence(ev('released', { actorName: 'Lena Lead', fromValue: 'agent:3', fromValueName: 'codex@docker2', toValue: 'ready', note: 'access removed' })))
      .toBe("Lena Lead removed codex@docker2's access to this project, ending its claim (now ready)");
  });
  it('released: sponsor access removed, by a person, names the agent whose sponsor lost access', () => {
    expect(eventSentence(ev('released', { actorName: 'Pat P', actorKind: 'human', fromValue: 'agent:3', fromValueName: 'codex@docker2', toValue: 'ready', note: 'sponsor access removed' })))
      .toBe("Pat P changed the access of codex@docker2's sponsor, ending its claim (now ready)");
  });
  it('released: sponsor access removed, by the sweep, reads as the agent losing its claim', () => {
    expect(eventSentence(ev('released', { actorName: 'codex@docker2', actorKind: 'agent', agentId: 3, fromValue: 'agent:3', fromValueName: 'codex@docker2', toValue: 'ready', note: 'sponsor access removed' })))
      .toBe('codex@docker2 lost its claim because its sponsor lost access (now ready)');
  });
  it('released: agent retired names what the actor retired', () => {
    expect(eventSentence(ev('released', { actorName: 'Admin A', fromValue: 'agent:3', fromValueName: 'codex@docker2', toValue: 'ready', note: 'agent retired' })))
      .toBe('Admin A retired codex@docker2, ending its claim (now ready)');
  });
  it('released: project archived names the archiver and the holder whose claim ended', () => {
    expect(eventSentence(ev('released', { actorName: 'Admin A', actorKind: 'human', fromValue: 'agent:3', fromValueName: 'codex@docker2', toValue: 'ready', note: 'project archived' })))
      .toBe("Admin A archived this project, ending codex@docker2's claim (now ready)");
    expect(eventText(ev('released', { actorName: 'Admin A', fromValue: 'agent:3', toValue: 'review', note: 'project archived' })).note).toBeNull();
  });
  it('released: an agent moving its own item ends its own claim', () => {
    const own = { actorName: 'codex@docker2', actorKind: 'agent', agentId: 3, fromValue: 'agent:3', fromValueName: 'codex@docker2', toValue: 'review' };
    expect(eventSentence(ev('released', { ...own, note: 'moved' }))).toBe('codex@docker2 moved this item, ending its own claim (now review)');
    expect(eventSentence(ev('released', { ...own, note: 'reassigned' }))).toBe('codex@docker2 handed this item on, ending its own claim (now review)');
  });
  it('released with neutral fallbacks', () => {
    expect(eventSentence(ev('released', { actorName: null, fromValue: 'agent:9', toValue: 'ready', note: 'moved' })))
      .toBe("Someone moved this item, ending an agent's claim (now ready)");
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
  it('an unknown type never prints a raw agent or queue value', () => {
    const t = eventText(ev('future_thing', { fromValue: 'agent:17', toValue: 'queue:q9', toValueName: 'Reviewer' })).text;
    expect(t).toBe('future thing: an agent → Reviewer');
    expect(eventText(ev('future_thing', { fromValue: 'queue:q9', toValue: 'agent:4' })).text).toBe('future thing: a queue → an agent');
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

  it('change events say what happened to the branch, never a raw change id', () => {
    const by = { actorName: 'codex@docker2', actorKind: 'agent', fromValue: 'change:7' };
    expect(eventSentence(ev('change_opened', { ...by, toValue: 'work/PLAT-7-x', note: 'aaaaaaa1bbbbcccc' })))
      .toBe('codex@docker2 opened a change on work/PLAT-7-x at aaaaaaa1');
    expect(eventSentence(ev('change_updated', { ...by, toValue: 'bbbbbbb2cccc' }))).toBe('codex@docker2 updated a change at bbbbbbb2');
    expect(eventSentence(ev('review_requested', { ...by, toValue: 'work/PLAT-7-x', note: null })))
      .toBe('codex@docker2 asked for a review of work/PLAT-7-x');
    expect(eventSentence(ev('change_reviewed', { ...by, toValue: 'approved', note: null }))).toBe('codex@docker2 approved a change');
    expect(eventSentence(ev('change_reviewed', { ...by, toValue: 'changes_requested', note: 'needs a test' })))
      .toBe("codex@docker2 sent a change back for more work — 'needs a test'");
    expect(eventSentence(ev('change_merged', { ...by, toValue: 'reviewed', note: 'aaaaaaa1' })))
      .toBe('codex@docker2 merged a reviewed change at aaaaaaa1');
    expect(eventSentence(ev('change_merged', { ...by, toValue: 'unreviewed', note: 'aaaaaaa1' })))
      .toBe('codex@docker2 merged a change without review at aaaaaaa1');
    expect(eventSentence(ev('change_merged', { ...by, toValue: 'direct', note: '9b01c77' }))).toBe('codex@docker2 merged directly at 9b01c77');
    expect(eventSentence(ev('change_abandoned', { ...by, toValue: 'work/PLAT-7-x', note: null })))
      .toBe('codex@docker2 abandoned the change on work/PLAT-7-x');
    expect(eventSentence(ev('acknowledged', { actorName: 'Mia Member', actorKind: 'human' })))
      .toBe('Mia Member acknowledged that an agent closed this with no person in the path');
    for (const type of ['change_opened', 'change_updated', 'review_requested', 'change_reviewed', 'change_merged', 'change_abandoned']) {
      expect(eventSentence(ev(type, { ...by, toValue: 'x' }))).not.toContain('change:7');
    }
  });

  it('deployment events name the version and environment, never a raw deployment id', () => {
    const by = { actorName: 'codex@docker2', actorKind: 'agent', fromValue: 'deployment:4', toValue: '2.5.26', note: 'production' };
    expect(eventSentence(ev('deployment_requested', by))).toBe('codex@docker2 asked for this to be deployed in 2.5.26 (production)');
    expect(eventSentence(ev('shipped', by))).toBe('codex@docker2 shipped this in 2.5.26 (production)');
    expect(eventSentence(ev('rolled_back', by))).toBe('codex@docker2 rolled back 2.5.26 (production), which carried this');
    for (const type of ['deployment_requested', 'shipped', 'rolled_back']) {
      expect(eventSentence(ev(type, by))).not.toContain('deployment:4');
    }
  });
});
