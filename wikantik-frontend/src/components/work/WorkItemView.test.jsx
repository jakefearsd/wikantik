import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  getItem: vi.fn(), patchItem: vi.fn(), listMembers: vi.fn(), listEvents: vi.fn(), listRevisions: vi.fn(),
  addComment: vi.fn(), editComment: vi.fn(), removeComment: vi.fn(), addLink: vi.fn(), removeLink: vi.fn(), listItems: vi.fn(),
}));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ user: { authenticated: true, loginPrincipal: 'ana-login' } }) }));

import {
  getItem, patchItem, listMembers, listEvents, listRevisions, addComment, addLink, listItems,
} from '../../api/work';
import WorkItemView from './WorkItemView';

const MEMBERS = [
  { principal: 'ana-login', principalKind: 'user', role: 'lead', displayName: 'Ana Alvarez' },
  { principal: 'bob-login', principalKind: 'user', role: 'member', displayName: 'Bob Brown' },
  { principal: 'ops', principalKind: 'group', role: 'member', displayName: 'ops' },
];
const makeView = (over = {}, itemOver = {}) => ({
  item: {
    id: 7, key: 'PLAT-7', level: 'task', title: 'Fix the flux capacitor', body: 'Original **body**', status: 'ready', priority: 3,
    dueOn: null, assignee: 'bob-login', assigneeName: 'Bob Brown', reporter: 'ana-login', reporterName: 'Ana Alvarez', kind: 'software',
    autonomy: null, doneMeans: null, allowDirect: null, version: 4, createdAt: '2026-11-01T10:00:00Z', updatedAt: '2026-11-02T10:00:00Z', ...itemOver,
  },
  bodyHtml: '<p>Original <strong>body</strong></p>', projectKey: 'PLAT', parentKey: 'PLAT-2', productKey: 'wikantik', derivedProductKeys: [],
  comments: [], links: [], blocked: false, viewerRole: 'member', ...over,
});

function renderView(view = makeView(), url = '/work/item/PLAT-7') {
  getItem.mockResolvedValue(view);
  return render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/work/item/:key" element={<WorkItemView />} />
        <Route path="/work/:project" element={<div>board page</div>} />
      </Routes>
    </MemoryRouter>);
}

beforeEach(() => {
  vi.clearAllMocks();
  listMembers.mockResolvedValue(MEMBERS);
  listEvents.mockResolvedValue([]);
  listRevisions.mockResolvedValue([]);
  listItems.mockResolvedValue([]);
});

const ready = () => screen.findByRole('heading', { level: 1, name: 'Fix the flux capacitor' });

describe('WorkItemView', () => {
  it('rendersBodyHtmlFromServer', async () => {
    renderView();
    await ready();
    expect(screen.getByText('body').tagName).toBe('STRONG');
    expect(getItem).toHaveBeenCalledWith('PLAT-7');
    expect(listMembers).toHaveBeenCalledWith('PLAT');
  });

  it('keeps the rendered body when the page re-renders around it (typing in the comment box)', async () => {
    renderView();
    await ready();
    const host = screen.getByText('body').closest('.work-body');
    const marker = document.createElement('span');
    marker.setAttribute('data-injected', 'yes');
    host.appendChild(marker);
    const box = screen.getByLabelText('Add a comment');
    fireEvent.change(box, { target: { value: 'typing' } });
    fireEvent.change(box, { target: { value: 'typing more' } });
    expect(host.querySelector('[data-injected]')).not.toBeNull();
    expect(screen.getByText('body')).toBeInTheDocument();
  });

  it('shows the key, level, and breadcrumbs to the board and the parent item', async () => {
    renderView();
    await ready();
    const nav = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(within(nav).getByRole('link', { name: 'PLAT' })).toHaveAttribute('href', '/work/PLAT');
    expect(within(nav).getByRole('link', { name: 'PLAT-2' })).toHaveAttribute('href', '/work/item/PLAT-2');
    expect(screen.getByText('PLAT-7')).toBeInTheDocument();
    expect(screen.getByText('task')).toBeInTheDocument();
  });

  it('fieldEditPatchesOnlyThatField', async () => {
    const next = makeView({}, { priority: 1, version: 5 });
    patchItem.mockResolvedValue(next);
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Priority' }));
    fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
    await waitFor(() => expect(patchItem).toHaveBeenCalledTimes(1));
    expect(patchItem).toHaveBeenCalledWith('PLAT-7', { expectedVersion: 4, changes: { priority: 1 } });
    // state is replaced by the returned view: the next edit sends the new version
    expect(await screen.findByText('P1')).toBeInTheDocument();
    patchItem.mockResolvedValue(makeView({}, { priority: 2, version: 6 }));
    fireEvent.click(screen.getByRole('button', { name: 'Edit Priority' }));
    fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
    await waitFor(() => expect(patchItem).toHaveBeenCalledTimes(2));
    expect(patchItem.mock.calls[1][1]).toEqual({ expectedVersion: 5, changes: { priority: 2 } });
  });

  it('assigns by login while showing display names, from project members only', async () => {
    patchItem.mockResolvedValue(makeView({}, { assignee: 'ana-login', assigneeName: 'Ana Alvarez', version: 5 }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Assignee' }));
    const select = screen.getByLabelText('Assignee');
    const labels = within(select).getAllByRole('option').map((o) => o.textContent);
    expect(labels).toEqual(['Unassigned', 'Ana Alvarez', 'Bob Brown']);
    fireEvent.change(select, { target: { value: 'ana-login' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Assignee' }));
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-7', { expectedVersion: 4, changes: { assignee: 'ana-login' } }));
    expect(screen.queryByText(/-login/)).toBeNull();
  });

  it('unassigning sends null', async () => {
    patchItem.mockResolvedValue(makeView({}, { assignee: null, assigneeName: null, version: 5 }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Assignee' }));
    fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Assignee' }));
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-7', { expectedVersion: 4, changes: { assignee: null } }));
  });

  it('saves the body with expectedVersion and changes.body only', async () => {
    patchItem.mockResolvedValue(makeView({ bodyHtml: '<p>New text</p>' }, { body: 'New text', version: 5 }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit text' }));
    const ta = screen.getByLabelText('Item text');
    expect(ta.value).toBe('Original **body**');
    fireEvent.change(ta, { target: { value: 'New text' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save text' }));
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-7', { expectedVersion: 4, changes: { body: 'New text' } }));
    expect(await screen.findByText('New text')).toBeInTheDocument();
    expect(screen.queryByLabelText('Item text')).toBeNull();
  });

  it('conflictKeepsUnsavedTextAndOffersReload', async () => {
    const theirs = makeView({ bodyHtml: '<p>Their rewrite</p>' }, { body: 'Their rewrite', version: 5 });
    patchItem.mockRejectedValue(Object.assign(new Error('changed'), { status: 409, current: theirs }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit text' }));
    fireEvent.change(screen.getByLabelText('Item text'), { target: { value: 'My unsaved words' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save text' }));
    const banner = await screen.findByRole('alert');
    expect(banner).toHaveTextContent('This item changed while you were editing');
    // the editor still holds what was typed
    expect(screen.getByLabelText('Item text').value).toBe('My unsaved words');
    // their version beside mine
    fireEvent.click(screen.getByRole('button', { name: 'Review their version' }));
    const review = screen.getByTestId('conflict-review');
    expect(within(review).getByText('Their rewrite')).toBeInTheDocument();
    expect(within(review).getByText('My unsaved words')).toBeInTheDocument();
    // Reload fetches the item again
    getItem.mockResolvedValue(theirs);
    fireEvent.click(screen.getByRole('button', { name: 'Reload' }));
    await waitFor(() => expect(getItem).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
    expect(await screen.findByText('Their rewrite')).toBeInTheDocument();
  });

  it('keeping the editor on top of their version rebases without discarding what was typed', async () => {
    const theirs = makeView({ bodyHtml: '<p>Their rewrite</p>' }, { body: 'Their rewrite', version: 5 });
    patchItem.mockRejectedValueOnce(Object.assign(new Error('changed'), { status: 409, current: theirs }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit text' }));
    fireEvent.change(screen.getByLabelText('Item text'), { target: { value: 'My unsaved words' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save text' }));
    await screen.findByRole('alert');
    fireEvent.click(screen.getByRole('button', { name: 'Review their version' }));
    fireEvent.click(screen.getByRole('button', { name: 'Keep editing on top of theirs' }));
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByLabelText('Item text').value).toBe('My unsaved words');
    patchItem.mockResolvedValue(makeView({}, { body: 'My unsaved words', version: 6 }));
    fireEvent.click(screen.getByRole('button', { name: 'Save text' }));
    await waitFor(() => expect(patchItem).toHaveBeenCalledTimes(2));
    expect(patchItem.mock.calls[1][1]).toEqual({ expectedVersion: 5, changes: { body: 'My unsaved words' } });
  });

  it('a conflict on a small field keeps that field\'s draft too', async () => {
    const theirs = makeView({}, { title: 'Their title', version: 5 });
    patchItem.mockRejectedValue(Object.assign(new Error('changed'), { status: 409, current: theirs }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'My title' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Title' }));
    await screen.findByRole('alert');
    expect(screen.getByLabelText('Title').value).toBe('My title');
    fireEvent.click(screen.getByRole('button', { name: 'Review their version' }));
    expect(within(screen.getByTestId('conflict-review')).getByText('Their title')).toBeInTheDocument();
  });

  it('handoffRequiresNote', async () => {
    patchItem.mockResolvedValue(makeView({}, { status: 'review', assignee: 'ana-login', assigneeName: 'Ana Alvarez', version: 5 }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Hand off' }));
    fireEvent.change(screen.getByLabelText('Status after handoff'), { target: { value: 'review' } });
    fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'ana-login' } });
    const note = screen.getByLabelText('Handoff note');
    expect(note).toBeRequired();
    const send = screen.getByRole('button', { name: 'Send handoff' });
    expect(send).toBeDisabled();
    fireEvent.click(send);
    expect(patchItem).not.toHaveBeenCalled();
    fireEvent.change(note, { target: { value: '   ' } });
    expect(send).toBeDisabled();
    fireEvent.change(note, { target: { value: 'Over to you, tests are green' } });
    expect(send).not.toBeDisabled();
    fireEvent.click(send);
    await waitFor(() => expect(patchItem).toHaveBeenCalledTimes(1));
    expect(patchItem).toHaveBeenCalledWith('PLAT-7', {
      expectedVersion: 4, changes: { status: 'review', assignee: 'ana-login' }, note: 'Over to you, tests are green',
    });
  });

  it('viewerSeesNoEditControls', async () => {
    renderView(makeView({ viewerRole: 'viewer', links: [{ id: 1, itemId: 7, kind: 'page', target: 'Main' }] }));
    await ready();
    expect(screen.queryByRole('button', { name: /^Edit / })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Hand off' })).toBeNull();
    expect(screen.queryByLabelText('Add a comment')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Add link' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Remove link/ })).toBeNull();
    expect(screen.getByText('Bob Brown')).toBeInTheDocument();
  });

  it('policyFieldsEditableOnlyByLead', async () => {
    const lead = renderView(makeView({ viewerRole: 'lead' }, { autonomy: 'delegated', doneMeans: 'merged', allowDirect: true }));
    await ready();
    expect(screen.getAllByText('Takes effect in a later release')).toHaveLength(3);
    expect(screen.getByText('Delegated')).toBeInTheDocument();
    expect(screen.getByText('Merged')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit Autonomy' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit Done means' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit Allow direct' })).toBeInTheDocument();
    lead.unmount();

    renderView(makeView({ viewerRole: 'member' }, { autonomy: 'delegated', doneMeans: 'merged', allowDirect: true }));
    await ready();
    expect(screen.getAllByText('Takes effect in a later release')).toHaveLength(3);
    expect(screen.getByText('Delegated')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit Autonomy' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Edit Done means' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Edit Allow direct' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Edit Priority' })).toBeInTheDocument();
  });

  it('a lead edits a policy field, sending one field', async () => {
    patchItem.mockResolvedValue(makeView({ viewerRole: 'lead' }, { allowDirect: false, version: 5 }));
    renderView(makeView({ viewerRole: 'lead' }));
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Allow direct' }));
    fireEvent.change(screen.getByLabelText('Allow direct'), { target: { value: 'false' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Allow direct' }));
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-7', { expectedVersion: 4, changes: { allowDirect: false } }));
  });

  it('notFoundShowsNeutralMessage', async () => {
    getItem.mockRejectedValue(Object.assign(new Error('Not found.'), { status: 404 }));
    const { container } = render(
      <MemoryRouter initialEntries={['/work/item/PLAT-999']}>
        <Routes><Route path="/work/item/:key" element={<WorkItemView />} /></Routes>
      </MemoryRouter>);
    expect(await screen.findByText('No such item')).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/project|PLAT|Not found/i);
    expect(listMembers).not.toHaveBeenCalled();
  });

  it('a 404 while saving also becomes the neutral state', async () => {
    patchItem.mockRejectedValue(Object.assign(new Error('Not found.'), { status: 404 }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Priority' }));
    fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
    expect(await screen.findByText('No such item')).toBeInTheDocument();
  });

  it('a 403 shows the server\'s reason in a toast and keeps the draft', async () => {
    patchItem.mockRejectedValue(Object.assign(new Error('Viewers cannot edit.'), { status: 403 }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Priority' }));
    fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Viewers cannot edit.'));
    expect(screen.getByLabelText('Priority').value).toBe('2');
  });

  it('a load failure other than 404 shows its message', async () => {
    getItem.mockRejectedValue(Object.assign(new Error('Server went away'), { status: 500 }));
    render(<MemoryRouter initialEntries={['/work/item/PLAT-7']}><Routes><Route path="/work/item/:key" element={<WorkItemView />} /></Routes></MemoryRouter>);
    expect(await screen.findByText('Server went away')).toBeInTheDocument();
  });

  it('adds a comment, then refreshes the item', async () => {
    addComment.mockResolvedValue({ id: 9 });
    renderView();
    await ready();
    fireEvent.change(screen.getByLabelText('Add a comment'), { target: { value: 'Looks good' } });
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    await waitFor(() => expect(addComment).toHaveBeenCalledWith('PLAT-7', 'Looks good'));
    await waitFor(() => expect(getItem).toHaveBeenCalledTimes(2));
  });

  it('adds a link, then refreshes the item', async () => {
    addLink.mockResolvedValue({ id: 3 });
    renderView();
    await ready();
    fireEvent.change(screen.getByLabelText('Link target'), { target: { value: 'Main' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add link' }));
    await waitFor(() => expect(addLink).toHaveBeenCalledWith('PLAT-7', { kind: 'page', target: 'Main' }));
    await waitFor(() => expect(getItem).toHaveBeenCalledTimes(2));
  });

  it('shows the blocked flag from the view and makes no request for the blocking item', async () => {
    renderView(makeView({ blocked: true, links: [{ id: 5, itemId: 7, kind: 'blocked_by', target: 'PLAT-2' }] }));
    await ready();
    expect(screen.getAllByText('Blocked').length).toBeGreaterThan(0);
    expect(getItem).toHaveBeenCalledTimes(1);
    expect(getItem).toHaveBeenCalledWith('PLAT-7');
  });

  it('an epic renders the epic view with its stories', async () => {
    listItems.mockImplementation(({ parent }) => Promise.resolve(parent === 'PLAT-1' ? [{ id: 2, key: 'PLAT-2', level: 'story', title: 'A story', status: 'done' }] : []));
    renderView(makeView({ derivedProductKeys: ['wikantik'], parentKey: null }, { key: 'PLAT-1', level: 'epic', title: 'Big epic' }), '/work/item/PLAT-1');
    await screen.findByRole('heading', { level: 1, name: 'Big epic' });
    expect(await screen.findByText('A story')).toBeInTheDocument();
    expect(screen.getByText('1 of 1 done (100%)')).toBeInTheDocument();
  });

  it('shows the timeline for the item', async () => {
    listEvents.mockResolvedValue([{ id: 1, type: 'created', at: '2026-11-01T10:00:00Z', actor: 'ana-login', actorName: 'Ana Alvarez' }]);
    renderView();
    await ready();
    expect(await screen.findByText('created this item')).toBeInTheDocument();
    expect(listEvents).toHaveBeenCalledWith('PLAT-7');
  });
});
