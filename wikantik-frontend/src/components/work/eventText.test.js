import { describe, it, expect } from 'vitest';
import { eventText, eventSentence, actorLabel } from './eventText';

const ev = (type, extra = {}) => ({ id: 1, type, fromValue: null, toValue: null, note: null, actorName: 'Ana A', actor: 'ana-login', ...extra });

describe('eventText', () => {
  it('the four sprint events name the sprint', () => {
    expect(eventText(ev('sprint_created', { note: 'Sprint 12' })).text).toBe('created sprint Sprint 12');
    expect(eventText(ev('sprint_updated', { note: 'Sprint 12' })).text).toBe('changed sprint Sprint 12');
    expect(eventText(ev('sprint_started', { note: 'Sprint 12' })).text).toBe('started sprint Sprint 12');
    expect(eventText(ev('sprint_closed', { note: 'Sprint 12' })).text).toBe('closed sprint Sprint 12');
  });
  it('a field event noted estimate keeps the generic text', () => {
    expect(eventText(ev('field', { note: 'estimate', fromValue: '3', toValue: '5' })).text).toBe('changed estimate 3 → 5');
    expect(eventText(ev('field', { note: 'sprint', fromValue: null, toValue: '7' })).text).toBe('changed sprint none → 7');
  });
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

  it('an agent target is named by its name, or neutrally when the name is missing', () => {
    expect(eventText(ev('assigned', { toValue: 'agent:5', toValueName: 'codex@docker2' })).text).toBe('assigned this to codex@docker2');
    expect(eventText(ev('assigned', { toValue: 'agent:5' })).text).toBe('assigned this to an agent');
  });
  it('a handoff with a missing side or no assignee still reads as a sentence', () => {
    expect(eventText(ev('handoff', { fromValue: null, toValue: 'review' })).text).toBe('handed this to nobody: none → review');
    expect(eventText(ev('handoff', { fromValue: 'draft', toValue: 'review|c', toValueName: 'Carol C' })).text)
      .toBe('handed this to Carol C: draft → review');
  });
  it('the actor is Someone, or An agent, when the server could not name them', () => {
    expect(actorLabel(undefined)).toBe('Someone');
    expect(actorLabel({ actorKind: 'agent' })).toBe('An agent');
    expect(actorLabel({ actorKind: 'agent', actorName: 'codex@docker2' })).toBe('codex@docker2');
    expect(eventSentence({ type: 'created', actorKind: 'agent' })).toBe('An agent created this item');
  });
  it('an absent event is described as something done', () => {
    expect(eventText(undefined)).toEqual({ text: 'did something', note: null });
    expect(eventSentence(null)).toBe('Someone did something');
  });
  it('a deployment event with no environment omits the brackets', () => {
    expect(eventText(ev('shipped', { toValue: 'v2.6.0' })).text).toBe('shipped this in v2.6.0');
    expect(eventText(ev('shipped', { toValue: 'v2.6.0', note: 'prod' })).text).toBe('shipped this in v2.6.0 (prod)');
  });
  it('a release with no holder, no destination and no note is still readable', () => {
    expect(eventText(ev('released', { actorKind: 'agent' }))).toEqual({ text: "released an agent's claim", note: null });
    expect(eventText(ev('released', { actorKind: 'agent', actorName: 'codex', fromValue: 'agent:3', fromValueName: 'codex', toValue: 'ready' })).text)
      .toBe('released its claim (back to ready)');
    expect(eventText(ev('released', { actorKind: 'agent', agentId: 3, fromValue: 'agent:3', fromValueName: 'codex', toValue: 'ready', note: 'moved' })).text)
      .toBe('moved this item, ending its own claim (now ready)');
    expect(eventText(ev('released', { actorKind: 'agent', agentId: 4, fromValue: 'agent:3', fromValueName: 'codex', toValue: 'ready', note: 'moved' })).text)
      .toBe("moved this item, ending codex's claim (now ready)");
  });
  it('a member event falls back to neutral words when no name was resolved', () => {
    expect(eventText(ev('member', { toValue: 'user:x:editor' })).text).toBe('added someone as editor');
    expect(eventText(ev('member', { toValue: 'agent:7:editor' })).text).toBe('added agent an agent as editor');
    expect(eventText(ev('member', { fromValue: 'user:x:editor' })).text).toBe('removed someone (editor)');
    expect(eventText(ev('member', { fromValue: 'nonsense' })).text).toBe('changed the members');
    expect(eventText(ev('member', {})).text).toBe('changed the members');
  });
  it('a link with no kind separator shows the value as it is', () => {
    expect(eventText(ev('linked', { toValue: 'PLAT-3' })).text).toBe('linked PLAT-3');
    expect(eventText(ev('unlinked', { fromValue: 'blocks:PLAT-3' })).text).toBe('removed link blocks PLAT-3');
    expect(eventText(ev('linked', {})).text).toBe('added a link');
    expect(eventText(ev('unlinked', {})).text).toBe('removed a link');
  });
  it('a claim that expired with no state to go back to says only that it expired', () => {
    expect(eventSentence(ev('claim_expired', { actorName: 'codex' }))).toBe('claim by codex expired');
    expect(eventSentence(ev('claim_expired', { toValue: 'ready' }))).toBe('claim by Ana A expired — back to ready');
  });
  it('a field change with no field name says "a field"', () => {
    expect(eventText(ev('field', { fromValue: 'a', toValue: 'b' })).text).toBe('changed a field a → b');
  });
});

describe('eventText labels and type', () => {
  it('changed labels none → api, ui', () => {
    expect(eventText(ev('field', { note: 'labels', fromValue: null, toValue: 'api,ui' })).text).toBe('changed labels none → api, ui');
  });
  it('changed type feature → bug', () => {
    expect(eventText(ev('field', { note: 'type', fromValue: 'feature', toValue: 'bug' })).text).toBe('changed type feature → bug');
  });
});

describe('eventText with empty values', () => {
  const TYPES = ['created', 'edited', 'status', 'assigned', 'handoff', 'claimed', 'claim_renewed', 'released', 'claim_expired',
    'field', 'commented', 'comment_edited', 'comment_removed', 'linked', 'unlinked', 'change_opened', 'change_updated',
    'review_requested', 'change_reviewed', 'change_merged', 'change_abandoned', 'deployment_requested', 'shipped',
    'rolled_back', 'acknowledged', 'member', 'settings', 'some_future_type'];
  it('never ends a sentence in a space or holds a doubled space', () => {
    for (const type of TYPES) {
      for (const empty of [null, '', undefined]) {
        const e = { id: 1, type, fromValue: empty, toValue: empty, note: empty, actorName: 'Ana A' };
        const { text } = eventText(e);
        expect(text, type).toBe(text.trim());
        expect(text, type).not.toMatch(/ {2}/);
        expect(text.length, type).toBeGreaterThan(0);
        const s = eventSentence(e);
        expect(s, type).toBe(s.trim());
        expect(s, type).not.toMatch(/ {2}/);
      }
    }
  });
});

describe('webhook settings events', () => {
  const hook = (toValue) => ({ type: 'settings', note: 'webhook', fromValue: 'hooks.example.com', toValue, actorName: 'Ana Alvarez' });
  it.each([
    ['added', 'added a webhook to hooks.example.com'],
    ['changed', 'changed the webhook to hooks.example.com'],
    ['removed', 'removed the webhook to hooks.example.com'],
    ['disabled', 'turned off the webhook to hooks.example.com'],
    ['enabled', 'turned on the webhook to hooks.example.com'],
  ])('webhook %s', (action, text) => {
    expect(eventText(hook(action)).text).toBe(text);
    expect(eventSentence(hook(action))).toBe(`Ana Alvarez ${text}`);
  });
  it('webhookFailedIsStandaloneAndDoesNotNameTheActor', () => {
    const t = eventText(hook('failed'));
    expect(t.standalone).toBe(true);
    expect(t.text).toBe('The webhook to hooks.example.com was switched off after failing for 24 hours.');
    expect(eventSentence(hook('failed'))).not.toContain('Ana Alvarez');
  });
});
