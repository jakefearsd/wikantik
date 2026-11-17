import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('./client', () => ({ collabRequest: vi.fn() }));
import { collabRequest } from './client';
import * as work from './work';
import { isArchivedRefusal } from '../components/work/archived';

beforeEach(() => { collabRequest.mockReset(); collabRequest.mockResolvedValue({}); });

describe('work api client', () => {
  it('asks the log for types, filters and the compound cursor', async () => {
    await work.getAssignmentLog({ types: ['claimed', 'released'], project: 'PLAT', actorKind: 'agent', agent: '7', before: '2026-11-16T10:00:00Z', beforeId: 9, limit: 50 });
    expect(collabRequest).toHaveBeenCalledWith('/api/work/log?types=claimed%2Creleased&project=PLAT&actorKind=agent&agent=7&before=2026-11-16T10%3A00%3A00Z&beforeId=9&limit=50');
  });

  it('exports one function per endpoint', () => {
    for (const name of ['listProjects', 'getProject', 'createProject', 'updateProject', 'archiveProject',
      'listMembers', 'putMember', 'removeMember', 'getBoard', 'listProducts', 'createProduct', 'updateProduct',
      'listItems', 'createItem', 'getItem', 'patchItem', 'listRevisions', 'listEvents', 'addComment',
      'editComment', 'removeComment', 'addLink', 'removeLink', 'getActivity', 'getAssignmentLog', 'getMe']) {
      expect(typeof work[name], name).toBe('function');
    }
  });

  it('builds paths, encodes keys and skips empty query values', async () => {
    await work.getBoard('PLAT', { product: 'wikantik', q: '', assignee: undefined });
    expect(collabRequest).toHaveBeenCalledWith('/api/work/projects/PLAT/board?product=wikantik');
    await work.listItems({ project: 'PLAT', status: ['draft', 'ready'] });
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/items?project=PLAT&status=draft%2Cready');
    await work.getItem('A B');
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/items/A%20B');
  });

  it('sends JSON bodies with the right method', async () => {
    await work.patchItem('PLAT-1', { expectedVersion: 2, changes: { status: 'ready' } });
    const [path, opts] = collabRequest.mock.calls[0];
    expect(path).toBe('/api/work/items/PLAT-1');
    expect(opts.method).toBe('PATCH');
    expect(JSON.parse(opts.body)).toEqual({ expectedVersion: 2, changes: { status: 'ready' } });
    await work.removeMember('PLAT', { principal: 'bob', principalKind: 'user' });
    expect(collabRequest.mock.calls[1][1].method).toBe('DELETE');
    expect(JSON.parse(collabRequest.mock.calls[1][1].body)).toEqual({ principal: 'bob', principalKind: 'user' });
    await work.addComment('PLAT-1', 'hi');
    expect(JSON.parse(collabRequest.mock.calls[2][1].body)).toEqual({ body: 'hi' });
  });

  it('patchItem rejects with status 409 and the current item on conflict', async () => {
    const current = { item: { key: 'PLAT-1', version: 5 } };
    collabRequest.mockRejectedValue(Object.assign(new Error('changed'), { status: 409, body: { current } }));
    await expect(work.patchItem('PLAT-1', { expectedVersion: 1, changes: {} }))
      .rejects.toMatchObject({ status: 409, current });
  });

  it('patchItem says archived and carries no current item when the project is archived', async () => {
    collabRequest.mockRejectedValue(Object.assign(new Error('This project is archived.'),
      { status: 409, body: { reason: 'archived', current: { item: {} } } }));
    const e = await work.patchItem('PLAT-1', { expectedVersion: 1, changes: {} }).catch((x) => x);
    expect(e).toMatchObject({ status: 409, reason: 'archived' });
    expect(e.current).toBeUndefined();
    expect(isArchivedRefusal(e)).toBe(true);
    expect(isArchivedRefusal({ status: 409, reason: 'stale' })).toBe(false);
  });

  it('patchItem passes other failures through untouched', async () => {
    const err = Object.assign(new Error('Not allowed'), { status: 403, body: {} });
    collabRequest.mockRejectedValue(err);
    await expect(work.patchItem('PLAT-1', { expectedVersion: 1, changes: {} })).rejects.toBe(err);
  });

  it('exports the phase 2 queue, agent and claim functions', () => {
    for (const name of ['listQueues', 'createQueue', 'updateQueue', 'retireQueue', 'listAgents', 'registerAgent',
      'updateAgent', 'rotateAgentKey', 'retireAgent', 'takeOverAgent', 'releaseAgentSponsorship', 'listClaimable', 'claimItem', 'releaseItem', 'handoffItem']) {
      expect(typeof work[name], name).toBe('function');
    }
  });

  it('addresses queues and agents with the right verbs and encoded keys', async () => {
    await work.listQueues();
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/queues');
    await work.createQueue({ key: 'triage', name: 'Triage', description: 'd' });
    expect(collabRequest.mock.calls.at(-1)[0]).toBe('/api/work/queues');
    expect(collabRequest.mock.calls.at(-1)[1].method).toBe('POST');
    await work.updateQueue('a b', { name: 'N' });
    expect(collabRequest.mock.calls.at(-1)[0]).toBe('/api/work/queues/a%20b');
    expect(collabRequest.mock.calls.at(-1)[1].method).toBe('PATCH');
    await work.retireQueue('triage');
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/queues/triage/retire', { method: 'POST' });
    await work.registerAgent({ name: 'bot', vendor: 'v', runtime: 'r', queues: ['triage'] });
    expect(JSON.parse(collabRequest.mock.calls.at(-1)[1].body)).toEqual({ name: 'bot', vendor: 'v', runtime: 'r', queues: ['triage'] });
    await work.updateAgent(7, { name: 'bot2' });
    expect(collabRequest.mock.calls.at(-1)[0]).toBe('/api/work/agents/7');
    await work.rotateAgentKey(7);
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/agents/7/rotate-key', { method: 'POST' });
    await work.takeOverAgent(7);
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/agents/7/sponsor', { method: 'PUT' });
    await work.releaseAgentSponsorship(7);
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/agents/7/sponsor', { method: 'DELETE' });
    await work.retireAgent(7);
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/agents/7/retire', { method: 'POST' });
  });

  it('addresses claims and builds the claimable query', async () => {
    await work.listClaimable({ project: 'PLAT', queue: 'triage', limit: '' });
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/claimable?project=PLAT&queue=triage');
    await work.claimItem('PLAT-1');
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/items/PLAT-1/claim', { method: 'POST' });
    await work.releaseItem('PLAT-1');
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/items/PLAT-1/release', { method: 'POST' });
    await work.releaseItem('PLAT-1', 'stuck');
    expect(JSON.parse(collabRequest.mock.calls.at(-1)[1].body)).toEqual({ note: 'stuck' });
    await work.handoffItem('PLAT-1', { expectedVersion: 2, status: 'review', note: 'n' });
    expect(collabRequest.mock.calls.at(-1)[0]).toBe('/api/work/items/PLAT-1/handoff');
  });

  it('handoffItem rejects with status, reason and current on a 409 like patchItem', async () => {
    const current = { item: { key: 'PLAT-1', version: 5 } };
    collabRequest.mockRejectedValue(Object.assign(new Error('x'), { status: 409, body: { reason: 'stale', current } }));
    await expect(work.handoffItem('PLAT-1', { expectedVersion: 1 }))
      .rejects.toMatchObject({ status: 409, reason: 'stale', current });
  });

  it('changes, the item policy and acknowledge', async () => {
    await work.listChanges('PLAT-7');
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/items/PLAT-7/changes');
    await work.openChange('PLAT-7', { branch: 'work/PLAT-7-x' });
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/items/PLAT-7/changes',
      { method: 'POST', body: JSON.stringify({ branch: 'work/PLAT-7-x' }) });
    await work.updateChange(5, { headSha: 'abc1234' });
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/changes/5', { method: 'PATCH', body: JSON.stringify({ headSha: 'abc1234' }) });
    await work.requestReview(5);
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/changes/5/request-review', { method: 'POST' });
    await work.reviewChange(5, { approved: true });
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/changes/5/review', { method: 'POST', body: JSON.stringify({ approved: true }) });
    await work.mergeChange(5, { mergedSha: 'abc1234' });
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/changes/5/merge', { method: 'POST', body: JSON.stringify({ mergedSha: 'abc1234' }) });
    await work.abandonChange(5, {});
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/changes/5/abandon', { method: 'POST', body: '{}' });
    await work.getChangeCounts('PLAT');
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/projects/PLAT/change-counts');
    await work.getItemPolicy('PLAT-7');
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/items/PLAT-7/policy');
    await work.acknowledgeItem('PLAT-7');
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/items/PLAT-7/acknowledge', { method: 'POST' });
  });

  it('deployments and what an item shipped in', async () => {
    await work.listDeployments('wikantik');
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/deployments?product=wikantik');
    await work.getDeployment(4);
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/deployments/4');
    await work.getDeploymentHistory(4);
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/deployments/4/history');
    await work.requestDeployment({ product: 'wikantik', version: '2.5.26', items: ['PLAT-1'] });
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/deployments',
      { method: 'POST', body: JSON.stringify({ product: 'wikantik', version: '2.5.26', items: ['PLAT-1'] }) });
    await work.approveDeployment(4, { note: 'go' });
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/deployments/4/approve', { method: 'POST', body: JSON.stringify({ note: 'go' }) });
    await work.rejectDeployment(4, { note: 'no' });
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/deployments/4/reject', { method: 'POST', body: JSON.stringify({ note: 'no' }) });
    await work.startDeployment(4);
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/deployments/4/start', { method: 'POST' });
    await work.verifyDeployment(4, { evidence: 'ok' });
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/deployments/4/verify', { method: 'POST', body: JSON.stringify({ evidence: 'ok' }) });
    await work.failDeployment(4, {});
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/deployments/4/fail', { method: 'POST', body: '{}' });
    await work.rollBackDeployment(4, {});
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/deployments/4/roll-back', { method: 'POST', body: '{}' });
    await work.getShippedIn('PLAT-1');
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/items/PLAT-1/shipped-in');
  });

  it('sends an empty JSON object when a write has no body and no query string when there are no filters', async () => {
    await work.createProject(undefined);
    expect(collabRequest.mock.calls[0][1]).toEqual({ method: 'POST', body: '{}' });
    await work.getBoard('PLAT');
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/projects/PLAT/board');
    await work.listItems();
    expect(collabRequest).toHaveBeenLastCalledWith('/api/work/items');
  });

  it('patchItem gives a plain-English default when a 409 arrives with no message', async () => {
    collabRequest.mockRejectedValue({ status: 409, body: { reason: 'archived' } });
    await expect(work.patchItem('PLAT-1', {})).rejects.toMatchObject({ message: 'This project is archived.', reason: 'archived' });
    collabRequest.mockRejectedValue({ status: 409, body: { current: { item: { key: 'PLAT-1' } } } });
    await expect(work.patchItem('PLAT-1', {})).rejects.toMatchObject({
      message: 'Item changed since it was loaded', reason: 'stale', current: { item: { key: 'PLAT-1' } },
    });
    collabRequest.mockRejectedValue({ status: 409 });
    await expect(work.patchItem('PLAT-1', {})).rejects.toMatchObject({ reason: 'stale', current: undefined });
  });
});
