import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  getItem: vi.fn(), patchItem: vi.fn(), listMembers: vi.fn(), listEvents: vi.fn(), listRevisions: vi.fn(),
  listProducts: vi.fn(), addComment: vi.fn(), editComment: vi.fn(), removeComment: vi.fn(), addLink: vi.fn(), removeLink: vi.fn(), listItems: vi.fn(),
  listQueues: vi.fn(), handoffItem: vi.fn(), releaseItem: vi.fn(),
}));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ user: { authenticated: true, loginPrincipal: 'ana-login' } }) }));

import {
  getItem, patchItem, listMembers, listProducts, listEvents, listRevisions, addComment, addLink, listItems,
  listQueues, handoffItem, releaseItem,
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
  listQueues.mockResolvedValue([
    { key: 'implementer', name: 'Implementer', retiredAt: null }, { key: 'reviewer', name: 'Reviewer', retiredAt: null },
    { key: 'old', name: 'Old queue', retiredAt: '2026-10-01T00:00:00Z' },
  ]);
  listProducts.mockResolvedValue([{ id: 3, key: 'wikantik', name: 'Wikantik' }, { id: 4, key: 'docs', name: 'Docs' }]);
});

const ready = () => screen.findByRole('heading', { level: 1, name: 'Fix the flux capacitor' });

describe('WorkItemView', () => {
  it('rendersBodyHtmlFromServer', async () => {
    renderView();
    await ready();
    expect(screen.getByText('body').tagName).toBe('STRONG');
    expect(getItem).toHaveBeenCalledWith('PLAT-7');
    // The members read is an effect that runs after the commit that shows the heading: wait for it, do not race it
    // (this failed once under --coverage, where rendering is slower).
    await waitFor(() => expect(listMembers).toHaveBeenCalledWith('PLAT'));
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
    expect(labels).toEqual(['Unassigned', 'Ana Alvarez', 'Bob Brown', 'Queue · Implementer', 'Queue · Reviewer']);
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
    // the draft differs from what the editor opened with, so discarding it asks first
    fireEvent.click(screen.getByRole('button', { name: 'Discard and reload' }));
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
    handoffItem.mockResolvedValue(makeView({}, { status: 'review', assignee: 'ana-login', assigneeName: 'Ana Alvarez', version: 5 }));
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
    expect(handoffItem).not.toHaveBeenCalled();
    fireEvent.change(note, { target: { value: '   ' } });
    expect(send).toBeDisabled();
    fireEvent.change(note, { target: { value: 'Over to you, tests are green' } });
    expect(send).not.toBeDisabled();
    fireEvent.click(send);
    await waitFor(() => expect(handoffItem).toHaveBeenCalledTimes(1));
    expect(handoffItem).toHaveBeenCalledWith('PLAT-7', {
      expectedVersion: 4, status: 'review', assignee: 'ana-login', note: 'Over to you, tests are green',
    });
    expect(patchItem).not.toHaveBeenCalled();
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

  it('offers exactly priorities 1 to 4, labelled', async () => {
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Priority' }));
    const labels = within(screen.getByLabelText('Priority')).getAllByRole('option').map((o) => o.textContent);
    expect(labels).toEqual(['P1 – highest', 'P2 – high', 'P3 – normal', 'P4 – lowest']);
  });

  it('productFieldPatchesProductKey', async () => {
    patchItem.mockResolvedValue(makeView({ productKey: 'docs' }, { version: 5 }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Product' }));
    await waitFor(() => expect(within(screen.getByLabelText('Product')).getAllByRole('option')).toHaveLength(3));
    fireEvent.change(screen.getByLabelText('Product'), { target: { value: 'docs' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Product' }));
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-7', { expectedVersion: 4, changes: { productKey: 'docs' } }));
  });

  it('clearingProductSendsNull', async () => {
    patchItem.mockResolvedValue(makeView({ productKey: null }, { version: 5 }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Product' }));
    fireEvent.change(screen.getByLabelText('Product'), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Product' }));
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-7', { expectedVersion: 4, changes: { productKey: null } }));
  });

  it('parentOptionsAreLimitedToTheParentLevel', async () => {
    listItems.mockImplementation(({ level }) => Promise.resolve(level === 'epic'
      ? [{ id: 1, key: 'PLAT-1', title: 'Big epic', level: 'epic' }, { id: 2, key: 'PLAT-2', title: 'Other epic', level: 'epic' }] : []));
    patchItem.mockResolvedValue(makeView({ parentKey: 'PLAT-1' }, { level: 'story', version: 5 }));
    renderView(makeView({}, { level: 'story' }));
    await ready();
    await waitFor(() => expect(listItems).toHaveBeenCalledWith({ project: 'PLAT', level: 'epic' }));
    fireEvent.click(screen.getByRole('button', { name: 'Edit Parent' }));
    const labels = within(screen.getByLabelText('Parent')).getAllByRole('option').map((o) => o.textContent);
    expect(labels).toEqual(['None', 'PLAT-1 – Big epic', 'PLAT-2 – Other epic']);
    expect(listItems).not.toHaveBeenCalledWith(expect.objectContaining({ level: 'story' }));
    fireEvent.change(screen.getByLabelText('Parent'), { target: { value: 'PLAT-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Parent' }));
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-7', { expectedVersion: 4, changes: { parentKey: 'PLAT-1' } }));
  });

  it('a task\'s parent candidates are stories', async () => {
    renderView();
    await ready();
    await waitFor(() => expect(listItems).toHaveBeenCalledWith({ project: 'PLAT', level: 'story' }));
  });

  it('epicShowsNoParentOrProductEditor', async () => {
    renderView(makeView({ derivedProductKeys: ['wikantik'], parentKey: null, productKey: null }, { key: 'PLAT-1', level: 'epic', title: 'Big epic' }));
    await screen.findByRole('heading', { level: 1, name: 'Big epic' });
    expect(screen.queryByRole('button', { name: 'Edit Parent' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Edit Product' })).toBeNull();
    expect(listProducts).not.toHaveBeenCalled();
    expect(screen.getAllByText('wikantik').length).toBeGreaterThan(0);
  });

  it('validationErrorIsShownBesideTheFieldAndValueRestored', async () => {
    patchItem.mockRejectedValue(Object.assign(new Error('An epic has no product of its own.'), {
      status: 400, body: { error: true, status: 400, field: 'productKey', message: 'Unknown product: nope' },
    }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Product' }));
    fireEvent.change(screen.getByLabelText('Product'), { target: { value: 'docs' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Product' }));
    const msg = await screen.findByText('Unknown product: nope');
    expect(msg.closest('.work-field')).toHaveTextContent('Product');
    expect(screen.queryByLabelText('Product')).toBeNull();
    expect(screen.getByRole('button', { name: 'Edit Product' })).toBeInTheDocument();
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('the in-queue status rule is shown beside the Status field, not as a toast', async () => {
    const rule = 'Work in a queue is ready or in review until an agent claims it.';
    patchItem.mockRejectedValue(Object.assign(new Error(rule), { status: 400, body: { field: 'status', message: rule } }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Status' }));
    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'in_progress' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Status' }));
    const msg = await screen.findByText(rule);
    expect(msg.closest('.work-field')).toHaveTextContent('Status');
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('viewers do not load products or parent candidates', async () => {
    renderView(makeView({ viewerRole: 'viewer' }));
    await ready();
    expect(listProducts).not.toHaveBeenCalled();
    expect(listItems).not.toHaveBeenCalled();
  });

  it('400 on the body keeps the typed text and shows the message beside the editor', async () => {
    patchItem.mockRejectedValue(Object.assign(new Error('bad'), { status: 400, body: { field: 'body', message: 'Text is too long.' } }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit text' }));
    fireEvent.change(screen.getByLabelText('Item text'), { target: { value: 'my long text' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save text' }));
    expect(await screen.findByText('Text is too long.')).toBeInTheDocument();
    expect(screen.getByLabelText('Item text').value).toBe('my long text');
  });

  it('400 on the title keeps the typed title and shows the message beside it', async () => {
    patchItem.mockRejectedValue(Object.assign(new Error('bad'), { status: 400, body: { field: 'title', message: 'A title is required.' } }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: ' ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Title' }));
    expect(await screen.findByText('A title is required.')).toBeInTheDocument();
    expect(screen.getByLabelText('Title').value).toBe(' ');
  });

  it('a 400 on the note during a handoff is shown in the handoff form and keeps it open', async () => {
    handoffItem.mockRejectedValue(Object.assign(new Error('bad'), { status: 400, body: { field: 'note', message: 'Changing status and assignee together needs a note.' } }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Hand off' }));
    fireEvent.change(screen.getByLabelText('Status after handoff'), { target: { value: 'review' } });
    fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'ana-login' } });
    fireEvent.change(screen.getByLabelText('Handoff note'), { target: { value: 'go' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send handoff' }));
    const msg = await screen.findByText('Changing status and assignee together needs a note.');
    expect(msg.closest('form')).not.toBeNull();
    expect(screen.getByLabelText('Handoff note').value).toBe('go');
  });

  it('a 409 from the handoff form keeps the note and the choices', async () => {
    const theirs = makeView({}, { status: 'done', version: 5 });
    handoffItem.mockRejectedValue(Object.assign(new Error('changed'), { status: 409, current: theirs }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Hand off' }));
    fireEvent.change(screen.getByLabelText('Status after handoff'), { target: { value: 'review' } });
    fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'ana-login' } });
    fireEvent.change(screen.getByLabelText('Handoff note'), { target: { value: 'my handoff note' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send handoff' }));
    await screen.findByRole('alert');
    expect(screen.getByLabelText('Handoff note').value).toBe('my handoff note');
    expect(screen.getByLabelText('Status after handoff').value).toBe('review');
    expect(screen.getByLabelText('Hand off to').value).toBe('ana-login');
  });

  it('a failed comment (409) keeps the typed comment', async () => {
    addComment.mockRejectedValue(Object.assign(new Error('Conflict'), { status: 409 }));
    renderView();
    await ready();
    fireEvent.change(screen.getByLabelText('Add a comment'), { target: { value: 'my comment' } });
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    await waitFor(() => expect(toast.error).toHaveBeenCalled());
    expect(screen.getByLabelText('Add a comment').value).toBe('my comment');
  });

  it('the conflict review shows display names, never logins, for assignee and handoff', async () => {
    const theirs = makeView({}, { assignee: 'bob-login', assigneeName: 'Bob Brown', version: 5 });
    handoffItem.mockRejectedValue(Object.assign(new Error('changed'), { status: 409, current: theirs }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Hand off' }));
    fireEvent.change(screen.getByLabelText('Status after handoff'), { target: { value: 'review' } });
    fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'ana-login' } });
    fireEvent.change(screen.getByLabelText('Handoff note'), { target: { value: 'note' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send handoff' }));
    await screen.findByRole('alert');
    fireEvent.click(screen.getByRole('button', { name: 'Review their version' }));
    const review = screen.getByTestId('conflict-review');
    expect(review).toHaveTextContent('Ana Alvarez');
    expect(review).not.toHaveTextContent('-login');
  });

  it('the conflict review shows their parent and product', async () => {
    const theirs = makeView({ parentKey: 'PLAT-9', productKey: 'docs' }, { version: 5 });
    patchItem.mockRejectedValue(Object.assign(new Error('changed'), { status: 409, current: theirs }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Product' }));
    fireEvent.change(screen.getByLabelText('Product'), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Product' }));
    await screen.findByRole('alert');
    fireEvent.click(screen.getByRole('button', { name: 'Review their version' }));
    expect(within(screen.getByTestId('conflict-review')).getByText('docs')).toBeInTheDocument();
  });

  it('a refetch after a comment raises the conflict banner for an open editor and keeps its base version', async () => {
    addComment.mockResolvedValue({ id: 9 });
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'My title' } });
    // someone else edited the item meanwhile
    getItem.mockResolvedValue(makeView({}, { title: 'Their title', version: 5 }));
    fireEvent.change(screen.getByLabelText('Add a comment'), { target: { value: 'hi' } });
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    const banner = await screen.findByRole('alert');
    expect(banner).toHaveTextContent('This item changed while you were editing');
    expect(screen.getByLabelText('Title').value).toBe('My title');
    // saving still sends the version the editor was opened against, so the server decides
    patchItem.mockRejectedValue(Object.assign(new Error('changed'), { status: 409, current: makeView({}, { title: 'Their title', version: 5 }) }));
    fireEvent.click(screen.getByRole('button', { name: 'Save Title' }));
    await waitFor(() => expect(patchItem).toHaveBeenCalledTimes(1));
    expect(patchItem.mock.calls[0][1].expectedVersion).toBe(4);
  });

  it('an editor with no draft open adopts the newest version after a refetch', async () => {
    addComment.mockResolvedValue({ id: 9 });
    renderView();
    await ready();
    getItem.mockResolvedValue(makeView({}, { version: 5 }));
    fireEvent.change(screen.getByLabelText('Add a comment'), { target: { value: 'hi' } });
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    await waitFor(() => expect(getItem).toHaveBeenCalledTimes(2));
    patchItem.mockResolvedValue(makeView({}, { priority: 1, version: 6 }));
    fireEvent.click(await screen.findByRole('button', { name: 'Edit Priority' }));
    fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-7', { expectedVersion: 5, changes: { priority: 1 } }));
  });

  it('a load that started before a save cannot replace the newer view', async () => {
    addComment.mockResolvedValue({ id: 9 });
    renderView();
    await ready();
    let resolveLoad;
    getItem.mockImplementation(() => new Promise((r) => { resolveLoad = r; }));
    fireEvent.change(screen.getByLabelText('Add a comment'), { target: { value: 'hi' } });
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    await waitFor(() => expect(getItem).toHaveBeenCalledTimes(2));
    patchItem.mockResolvedValue(makeView({}, { priority: 1, version: 5 }));
    fireEvent.click(screen.getByRole('button', { name: 'Edit Priority' }));
    fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
    expect(await screen.findByText('P1')).toBeInTheDocument();
    resolveLoad(makeView({}, { priority: 3, version: 4 }));
    await new Promise((r) => setTimeout(r, 0));
    expect(screen.getByText('P1')).toBeInTheDocument();
  });

  const spyConsole = () => vi.spyOn(console, 'error').mockImplementation((...a) => {
    if (String(a[0]).includes('Maximum update depth')) throw new Error('update depth loop');
  });

  it('onlyOneEditorOpenAtATime', async () => {
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    for (const name of ['Edit Status', 'Edit Priority', 'Edit text', 'Edit Parent']) {
      expect(screen.getByRole('button', { name })).toBeDisabled();
    }
    expect(screen.getByRole('button', { name: 'Hand off' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Hand off' })).toHaveAccessibleDescription('Finish or cancel the open edit first');
    fireEvent.click(screen.getByRole('button', { name: 'Cancel editing Title' }));
    for (const name of ['Edit Title', 'Edit Status', 'Edit Priority', 'Edit text', 'Hand off']) {
      expect(screen.getByRole('button', { name })).not.toBeDisabled();
    }
  });

  it('opening the body editor or the handoff form locks the field editors too', async () => {
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit text' }));
    expect(screen.getByRole('button', { name: 'Edit Title' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    fireEvent.click(screen.getByRole('button', { name: 'Hand off' }));
    expect(screen.getByRole('button', { name: 'Edit Title' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Edit text' })).toBeDisabled();
  });

  it('ownSaveNeverRaisesAConflict', async () => {
    const spy = spyConsole();
    patchItem.mockResolvedValueOnce(makeView({}, { title: 'Second', version: 5 })).mockResolvedValueOnce(makeView({}, { title: 'Second', priority: 1, version: 6 }));
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Second' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Title' }));
    await screen.findByRole('heading', { level: 1, name: 'Second' });
    expect(screen.queryByRole('alert')).toBeNull();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit Priority' }));
    expect(screen.queryByRole('alert')).toBeNull();
    fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
    await waitFor(() => expect(patchItem).toHaveBeenCalledTimes(2));
    expect(patchItem.mock.calls.map((c) => c[1].expectedVersion)).toEqual([4, 5]);
    expect(screen.queryByRole('alert')).toBeNull();
    spy.mockRestore();
  });

  it('refetchWithNewerVersionWhileEditingShowsBannerOnce', async () => {
    const spy = spyConsole();
    addComment.mockResolvedValue({ id: 9 });
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'My title' } });
    getItem.mockResolvedValue(makeView({}, { priority: 1, version: 5 }));
    fireEvent.change(screen.getByLabelText('Add a comment'), { target: { value: 'hi' } });
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    await waitFor(() => expect(screen.getAllByRole('alert')).toHaveLength(1));
    expect(screen.getByLabelText('Title').value).toBe('My title');
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(spy).not.toHaveBeenCalledWith(expect.stringContaining('Maximum update depth'), expect.anything());
    spy.mockRestore();
  });

  it('a comment alone does not move the item version, so it raises no banner', async () => {
    addComment.mockResolvedValue({ id: 9 });
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    fireEvent.change(screen.getByLabelText('Add a comment'), { target: { value: 'hi' } });
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    await waitFor(() => expect(getItem).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('keepEditingIsDisabledUntilReviewWhenSameFieldChanged', async () => {
    addComment.mockResolvedValue({ id: 9 });
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'My title' } });
    getItem.mockResolvedValue(makeView({}, { title: 'Their title', version: 5 }));
    fireEvent.change(screen.getByLabelText('Add a comment'), { target: { value: 'hi' } });
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    const keep = await screen.findByRole('button', { name: 'Keep editing on top of theirs' });
    expect(keep).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Review their version' }));
    expect(within(screen.getByTestId('conflict-review')).getByText('Their title')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Keep editing on top of theirs' })).not.toBeDisabled();
  });

  it('keepEditingOnOtherFieldChangeSendsCurrentVersionAndOnlyThatField', async () => {
    addComment.mockResolvedValue({ id: 9 });
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'My title' } });
    getItem.mockResolvedValue(makeView({}, { priority: 1, version: 5 }));
    fireEvent.change(screen.getByLabelText('Add a comment'), { target: { value: 'hi' } });
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    const keep = await screen.findByRole('button', { name: 'Keep editing on top of theirs' });
    expect(keep).not.toBeDisabled();
    fireEvent.click(keep);
    expect(screen.queryByRole('alert')).toBeNull();
    patchItem.mockResolvedValue(makeView({}, { title: 'My title', priority: 1, version: 6 }));
    fireEvent.click(screen.getByRole('button', { name: 'Save Title' }));
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-7', { expectedVersion: 5, changes: { title: 'My title' } }));
  });

  it('Reload with an unchanged editor needs no confirmation', async () => {
    addComment.mockResolvedValue({ id: 9 });
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    getItem.mockResolvedValue(makeView({}, { priority: 1, version: 5 }));
    fireEvent.change(screen.getByLabelText('Add a comment'), { target: { value: 'hi' } });
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Reload' }));
    expect(screen.queryByRole('button', { name: 'Discard and reload' })).toBeNull();
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
    expect(screen.queryByLabelText('Title')).toBeNull();
    expect(screen.getByRole('button', { name: 'Edit Priority' })).not.toBeDisabled();
  });

  it('an archived project shows the item read-only with a banner', async () => {
    renderView(makeView({ archived: true, viewerRole: 'lead' }));
    await ready();
    expect(screen.getByText('This project is archived. It is read-only.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit text' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Edit Priority' })).toBeNull();
    expect(screen.queryByLabelText('Add a comment')).toBeNull();
  });

  it('a save refused because the project was archived says so and is never an edit conflict', async () => {
    patchItem.mockRejectedValue(Object.assign(new Error('This project is archived.'), { status: 409, reason: 'archived' }));
    renderView();
    await ready();
    getItem.mockResolvedValue(makeView({ archived: true }));
    fireEvent.click(screen.getByRole('button', { name: 'Edit Priority' }));
    fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
    await waitFor(() => expect(toast.info).toHaveBeenCalledWith('This project is archived; nothing was changed.'));
    expect(screen.queryByText('This item changed while you were editing.')).toBeNull();
    expect(await screen.findByText('This project is archived. It is read-only.')).toBeInTheDocument();
  });

  it('the product picker leaves out archived products', async () => {
    listProducts.mockResolvedValue([{ key: 'wikantik', name: 'Wikantik', archived: false },
      { key: 'old', name: 'Old', archived: true }]);
    renderView(makeView({ productKey: null }));
    await ready();
    await waitFor(() => expect(listProducts).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: 'Edit Product' }));
    const select = screen.getByLabelText('Product');
    expect(within(select).getByRole('option', { name: 'wikantik – Wikantik' })).toBeInTheDocument();
    expect(within(select).queryByRole('option', { name: 'old – Old' })).toBeNull();
  });

  it('the text editor labels the rendered tab as the saved version, not a preview', async () => {
    renderView();
    await ready();
    fireEvent.click(screen.getByRole('button', { name: 'Edit text' }));
    expect(screen.queryByRole('button', { name: 'Preview' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Saved version' }));
    expect(screen.getByText(/last saved version, not your draft/)).toBeInTheDocument();
  });

  describe('queues and claims', () => {
    // The panel formats times against the clock; freeze Date (timers stay real) so 'since 13:05' holds on any day.
    beforeEach(() => { vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date(2026, 10, 16, 14, 0)); });
    afterEach(() => vi.useRealTimers());
    const CLAIM = {
      assignee: null, assigneeName: null, queue: 'implementer', queueName: 'Implementer', status: 'in_progress',
      claimedByAgent: 3, claimedByName: 'codex@docker2', claimedAt: new Date(2026, 10, 16, 13, 5).toISOString(),
      claimExpiresAt: new Date(2099, 10, 16, 15, 40).toISOString(),
    };
    const claimedView = (over = {}) => makeView(over, CLAIM);
    const queued = (over = {}) => makeView(over, { assignee: null, assigneeName: null, queue: 'implementer', queueName: 'Implementer', status: 'ready' });

    it('assigneeFieldUsesPicker: queues are offered next to people, and a queue is sent alone', async () => {
      patchItem.mockResolvedValue(queued({}));
      renderView();
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Edit Assignee' }));
      const options = [...screen.getByLabelText('Assignee').querySelectorAll('option')].map((o) => o.textContent);
      expect(options).toEqual(['Unassigned', 'Ana Alvarez', 'Bob Brown', 'Queue · Implementer', 'Queue · Reviewer']);
      fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: 'queue:implementer' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save Assignee' }));
      await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-7', { expectedVersion: 4, changes: { queue: 'implementer' } }));
    });

    it('choosing a person for a queued item sends the person alone; unassigning it clears the queue', async () => {
      patchItem.mockResolvedValue(queued({}));
      renderView(queued());
      await ready();
      expect(screen.getByText('Queue · Implementer')).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Edit Assignee' }));
      fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: 'ana-login' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save Assignee' }));
      await waitFor(() => expect(patchItem).toHaveBeenLastCalledWith('PLAT-7', { expectedVersion: 4, changes: { assignee: 'ana-login' } }));
      fireEvent.click(await screen.findByRole('button', { name: 'Edit Assignee' }));
      fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: '' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save Assignee' }));
      await waitFor(() => expect(patchItem).toHaveBeenCalledTimes(2));
      expect(patchItem.mock.calls[1][1].changes).toEqual({ queue: null });
    });

    it('an item still in a retired queue shows that queue', async () => {
      renderView(makeView({}, { assignee: null, assigneeName: null, queue: 'old', queueName: 'Old queue' }));
      await ready();
      expect(screen.getByText('Queue · Old queue')).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Edit Assignee' }));
      expect(screen.getByLabelText('Assignee').value).toBe('queue:old');
    });

    it('rendersClaimPanel: holder, since and until, with a Release action for a member', async () => {
      renderView(claimedView());
      await ready();
      const panel = screen.getByRole('region', { name: 'Claim' });
      expect(panel).toHaveTextContent('Claimed by codex@docker2');
      expect(panel).toHaveTextContent('since 13:05');
      expect(screen.getByRole('button', { name: 'Release claim' })).toBeInTheDocument();
    });

    it('releasing asks first, sends the note, then re-reads the item', async () => {
      releaseItem.mockResolvedValue({});
      renderView(claimedView());
      await ready();
      getItem.mockResolvedValue(queued({}));
      fireEvent.click(screen.getByRole('button', { name: 'Release claim' }));
      expect(releaseItem).not.toHaveBeenCalled();
      fireEvent.change(screen.getByLabelText('Release note (optional)'), { target: { value: 'stuck' } });
      fireEvent.click(screen.getByRole('button', { name: 'Release it' }));
      await waitFor(() => expect(releaseItem).toHaveBeenCalledWith('PLAT-7', 'stuck'));
      await waitFor(() => expect(screen.queryByRole('region', { name: 'Claim' })).toHaveTextContent('Claimable by agents serving Implementer'));
      expect(toast.success).toHaveBeenCalledWith("Released codex@docker2's claim");
    });

    it('a viewer sees the claim but no release, hand-off or edit', async () => {
      renderView(claimedView({ viewerRole: 'viewer' }));
      await ready();
      expect(screen.getByRole('region', { name: 'Claim' })).toHaveTextContent('Claimed by codex@docker2');
      expect(screen.queryByRole('button', { name: 'Release claim' })).toBeNull();
    });

    it('an archived project shows the claim read-only: no release, no hand-off', async () => {
      renderView(claimedView({ archived: true }));
      await ready();
      expect(screen.getByRole('region', { name: 'Claim' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Release claim' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'Hand off' })).toBeNull();
    });

    it('a release refused because the project is archived says so and shows no conflict', async () => {
      releaseItem.mockRejectedValue(Object.assign(new Error('This project is archived.'), { status: 409, reason: 'archived' }));
      renderView(claimedView());
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Release claim' }));
      fireEvent.click(screen.getByRole('button', { name: 'Release it' }));
      await waitFor(() => expect(toast.info).toHaveBeenCalledWith(expect.stringContaining('This project is archived')));
      expect(screen.queryByText(/changed while you were editing/)).toBeNull();
    });

    it('a claimed item warns, beside the controls, that moving or reassigning it releases the claim', async () => {
      renderView(claimedView());
      await ready();
      expect(screen.getByText("codex@docker2 is working on this; moving it releases its claim.")).toBeInTheDocument();
      expect(screen.getByText("codex@docker2 is working on this; reassigning it releases its claim.")).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Hand off' }));
      expect(screen.getByText('codex@docker2 is working on this; handing it off releases its claim.')).toBeInTheDocument();
    });

    it('an unclaimed item shows no claim warning', async () => {
      renderView(queued());
      await ready();
      expect(screen.queryByText(/is working on this/)).toBeNull();
    });

    it('a person moving a claimed item sees that the claim was released', async () => {
      patchItem.mockResolvedValue(queued({}));
      renderView(claimedView());
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Edit Status' }));
      fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'ready' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save Status' }));
      await waitFor(() => expect(toast.info).toHaveBeenCalledWith("codex@docker2's claim was released."));
    });

    it('a stale hand-off keeps the note, reloads their version and shows the conflict banner', async () => {
      const theirs = queued({});
      handoffItem.mockRejectedValue(Object.assign(new Error('changed'), { status: 409, reason: 'stale', current: theirs }));
      renderView();
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Hand off' }));
      fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'queue:reviewer' } });
      fireEvent.change(screen.getByLabelText('Handoff note'), { target: { value: 'my note' } });
      fireEvent.click(screen.getByRole('button', { name: 'Send handoff' }));
      expect(await screen.findByText(/This item changed while you were editing/)).toBeInTheDocument();
      expect(screen.getByLabelText('Handoff note').value).toBe('my note');
      fireEvent.click(screen.getByRole('button', { name: 'Review their version' }));
      const review = screen.getByTestId('conflict-review');
      expect(review).toHaveTextContent('Queue · Implementer');
      expect(review).not.toHaveTextContent('queue:');
    });

    it('a hand-off refused because the project is archived says so, not a conflict', async () => {
      handoffItem.mockRejectedValue(Object.assign(new Error('archived'), { status: 409, reason: 'archived' }));
      renderView();
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Hand off' }));
      fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'queue:reviewer' } });
      fireEvent.change(screen.getByLabelText('Handoff note'), { target: { value: 'n' } });
      fireEvent.click(screen.getByRole('button', { name: 'Send handoff' }));
      await waitFor(() => expect(toast.info).toHaveBeenCalledWith(expect.stringContaining('This project is archived')));
      expect(screen.queryByText(/changed while you were editing/)).toBeNull();
    });

    it('handing off to a queue sends the queue and no assignee', async () => {
      handoffItem.mockResolvedValue(queued({}));
      renderView();
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Hand off' }));
      fireEvent.change(screen.getByLabelText('Status after handoff'), { target: { value: 'review' } });
      fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'queue:reviewer' } });
      fireEvent.change(screen.getByLabelText('Handoff note'), { target: { value: 'ready for review' } });
      fireEvent.click(screen.getByRole('button', { name: 'Send handoff' }));
      await waitFor(() => expect(handoffItem).toHaveBeenCalledWith('PLAT-7',
        { expectedVersion: 4, status: 'review', queue: 'reviewer', note: 'ready for review' }));
    });

    it('a release that finds the claim already ended says so neutrally and refreshes to unclaimed', async () => {
      releaseItem.mockRejectedValue(Object.assign(new Error('Item is not claimed.'), { status: 409, body: { reason: 'not_claimed' } }));
      renderView(claimedView());
      await ready();
      getItem.mockResolvedValue(queued({}));
      fireEvent.click(screen.getByRole('button', { name: 'Release claim' }));
      fireEvent.click(screen.getByRole('button', { name: 'Release it' }));
      await waitFor(() => expect(toast.info).toHaveBeenCalledWith('This claim had already ended.'));
      expect(toast.error).not.toHaveBeenCalled();
      await waitFor(() => expect(screen.getByRole('region', { name: 'Claim' })).toHaveTextContent('Claimable by agents serving Implementer'));
    });

    it('a lapsed claim is not described as active work', async () => {
      renderView(makeView({}, { ...CLAIM, claimExpiresAt: new Date(2026, 10, 16, 13, 30).toISOString() }));
      await ready();
      expect(screen.queryByText(/is working on this/)).toBeNull();
      expect(screen.getByText("codex@docker2's claim has lapsed and will be cleared; moving this item clears it now.")).toBeInTheDocument();
      expect(screen.getByText("codex@docker2's claim has lapsed and will be cleared; reassigning this item clears it now.")).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Hand off' }));
      expect(screen.getByText("codex@docker2's claim has lapsed and will be cleared; handing it off clears it now.")).toBeInTheDocument();
    });
  });
});
