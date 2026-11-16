import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('./client', () => ({ collabRequest: vi.fn() }));
import { collabRequest } from './client';
import * as work from './work';
import { isArchivedRefusal } from '../components/work/archived';

beforeEach(() => { collabRequest.mockReset(); collabRequest.mockResolvedValue({}); });

describe('work api client', () => {
  it('exports one function per endpoint', () => {
    for (const name of ['listProjects', 'getProject', 'createProject', 'updateProject', 'archiveProject',
      'listMembers', 'putMember', 'removeMember', 'getBoard', 'listProducts', 'createProduct', 'updateProduct',
      'listItems', 'createItem', 'getItem', 'patchItem', 'listRevisions', 'listEvents', 'addComment',
      'editComment', 'removeComment', 'addLink', 'removeLink', 'getActivity', 'getMe']) {
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
});
