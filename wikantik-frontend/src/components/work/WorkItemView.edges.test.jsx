import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  getItem: vi.fn(), patchItem: vi.fn(), listMembers: vi.fn(), listEvents: vi.fn(), listRevisions: vi.fn(),
  listProducts: vi.fn(), addComment: vi.fn(), editComment: vi.fn(), removeComment: vi.fn(), addLink: vi.fn(), removeLink: vi.fn(), listItems: vi.fn(),
  listQueues: vi.fn(), handoffItem: vi.fn(), releaseItem: vi.fn(),
  // the changes panel and the policy note load their own data; these tests leave both empty unless they say otherwise
  listChanges: vi.fn(() => Promise.resolve({ changes: [] })), getItemPolicy: vi.fn(() => Promise.resolve(null)),
  openChange: vi.fn(), requestReview: vi.fn(), reviewChange: vi.fn(), mergeChange: vi.fn(), abandonChange: vi.fn(),
  acknowledgeItem: vi.fn(), getShippedIn: vi.fn(() => Promise.resolve({ shippedIn: [] })),
}));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ user: { authenticated: true, loginPrincipal: 'ana-login' } }) }));

import {
  getItem, patchItem, listMembers, listProducts, listEvents, listRevisions, addComment, listItems,
  listQueues, handoffItem, releaseItem, listChanges, getItemPolicy, getShippedIn,
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
  listChanges.mockResolvedValue({ changes: [] });
  getItemPolicy.mockResolvedValue(null);
  getShippedIn.mockResolvedValue({ shippedIn: [] });
});


const ready = async () => {
  const heading = await screen.findByRole('heading', { level: 1, name: 'Fix the flux capacitor' });
  await waitFor(() => expect(listMembers).toHaveBeenCalled());
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
  return heading;
};
const rejection = (status, extra = {}, message = 'changed') => Object.assign(new Error(message), { status, ...extra });
const comment = async (text = 'hi') => {
  fireEvent.change(screen.getByLabelText('Add a comment'), { target: { value: text } });
  fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
};
const review = () => {
  fireEvent.click(screen.getByRole('button', { name: 'Review their version' }));
  return screen.getByTestId('conflict-review');
};

describe('WorkItemView edges', () => {
  describe('a refetch while an editor is open compares that editor\'s field with the server', () => {
    // [open the editor, item fields before, view fields before, item fields after, view fields after]
    const FIELDS = [
      ['parent', 'Edit Parent', {}, { parentKey: null }, {}, { parentKey: 'PLAT-9' }, 'lead'],
      ['product', 'Edit Product', {}, { productKey: null }, {}, { productKey: 'docs' }, 'lead'],
      ['assignee', 'Edit Assignee', { assignee: null, assigneeName: null }, {}, { assignee: 'ana-login', assigneeName: 'Ana Alvarez' }, {}, 'lead'],
      ['priority', 'Edit Priority', {}, {}, { priority: 1 }, {}, 'lead'],
      ['due date', 'Edit Due', { dueOn: null }, {}, { dueOn: '2026-12-01' }, {}, 'lead'],
      ['kind', 'Edit Kind', { kind: null }, {}, { kind: 'docs' }, {}, 'lead'],
      ['allow direct', 'Edit Direct commits by agents', { allowDirect: null }, {}, { allowDirect: true }, {}, 'lead'],
      ['autonomy', 'Edit Autonomy', { autonomy: null }, {}, { autonomy: 'delegated' }, {}, 'lead'],
      ['done means', 'Edit Done means', { doneMeans: null, level: 'epic', productKey: null }, {}, { doneMeans: 'merged' }, {}, 'lead'],
      ['status', 'Edit Status', {}, {}, { status: 'review' }, {}, 'lead'],
      ['text', 'Edit text', { body: null }, {}, { body: 'Brand new text' }, {}, 'lead'],
      ['hand-off', 'Hand off', {}, {}, { status: 'review' }, {}, 'member'],
    ];
    it.each(FIELDS)('%s: keep editing waits for a review only once they changed the same field', async (_n, button, itemBefore, viewBefore, itemAfter, viewAfter, role) => {
      addComment.mockResolvedValue({ id: 9 });
      renderView(makeView({ viewerRole: role, ...viewBefore }, itemBefore));
      await ready();
      fireEvent.click(screen.getByRole('button', { name: button }));
      // someone changed a different field: the editor may keep going on top
      getItem.mockResolvedValue(makeView({ viewerRole: role, ...viewBefore }, { ...itemBefore, version: 5, reporterName: 'Someone Else' }));
      await comment('one');
      const keep = await screen.findByRole('button', { name: 'Keep editing on top of theirs' });
      expect(keep).toBeEnabled();
      // then they changed this very field
      getItem.mockResolvedValue(makeView({ viewerRole: role, ...viewBefore, ...viewAfter }, { ...itemBefore, ...itemAfter, version: 6 }));
      await comment('two');
      await waitFor(() => expect(screen.getByRole('button', { name: 'Keep editing on top of theirs' })).toBeDisabled());
    });
  });

  describe('the stale banner for a queue picked in the assignee editor', () => {
    const staleWithQueuePicked = async () => {
      addComment.mockResolvedValue({ id: 9 });
      renderView(makeView({ viewerRole: 'lead' }));
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Edit Assignee' }));
      await waitFor(() => expect(screen.getByLabelText('Assignee').querySelector('option[value="queue:implementer"]')).not.toBeNull());
      fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: 'queue:implementer' } });
      getItem.mockResolvedValue(makeView({ viewerRole: 'lead' }, { version: 5, reporterName: 'Someone Else' }));
      await comment('one');
      await screen.findByRole('button', { name: 'Review their version' });
      return review();
    };

    it('shows the queue by name, not "someone" and not the raw control string', async () => {
      const panel = await staleWithQueuePicked();
      expect(panel).toHaveTextContent('Queue · Implementer');
      expect(panel).not.toHaveTextContent('someone');
      expect(panel).not.toHaveTextContent('queue:');
    });

    it('falls back to the queue key when its name is unknown', async () => {
      listQueues.mockResolvedValue([{ key: 'implementer', name: '', retiredAt: null }]);
      const panel = await staleWithQueuePicked();
      expect(panel).toHaveTextContent('Queue · implementer');
    });
  });

  describe('a refused save whose body has no usable current item', () => {
    it.each([['an empty current', {}], ['a current with no item', { item: null }]])('does not throw with %s and says their value is unavailable', async (_n, current) => {
      patchItem.mockRejectedValue(rejection(409, { current }));
      renderView();
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Edit Priority' }));
      fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '1' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
      expect(await screen.findByText('This item changed while you were editing.')).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Review their version' }));
      expect(screen.getByTestId('conflict-review')).toHaveTextContent('(unavailable)');
      expect(screen.getByLabelText('Priority')).toBeInTheDocument();
    });
  });

  describe('reviewing their version after a refused save', () => {
    const conflictOn = async (current, open, change) => {
      patchItem.mockRejectedValue(rejection(409, { current }));
      renderView(makeView({ viewerRole: 'lead' }, { dueOn: '2026-12-24', assignee: 'bob-login' }));
      await ready();
      fireEvent.click(screen.getByRole('button', { name: open }));
      await change();
      fireEvent.click(screen.getByRole('button', { name: `Save ${open.replace('Edit ', '')}` }));
      await screen.findByRole('alert');
      return review();
    };

    it('shows their queue and "(nobody)" for your unassigning', async () => {
      const theirs = makeView({}, { assignee: null, assigneeName: null, queue: 'implementer', queueName: 'Implementer', version: 5 });
      const panel = await conflictOn(theirs, 'Edit Assignee', () => fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: '' } }));
      expect(panel).toHaveTextContent('Queue · Implementer');
      expect(panel).toHaveTextContent('(nobody)');
    });

    it('calls their queue "a queue" when it has no name', async () => {
      const theirs = makeView({}, { assignee: null, queue: 'implementer', queueName: null, version: 5 });
      const panel = await conflictOn(theirs, 'Edit Assignee', () => fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: '' } }));
      expect(panel).toHaveTextContent('Queue · a queue');
    });

    it('shows "(nobody)" for their assignee when they cleared it, and your pick by display name', async () => {
      const theirs = makeView({}, { assignee: null, assigneeName: null, version: 5 });
      const panel = await conflictOn(theirs, 'Edit Assignee', () => fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: 'ana-login' } }));
      expect(within(panel).getByText('Ana Alvarez')).toBeInTheDocument();
      expect(panel).toHaveTextContent('(nobody)');
      expect(panel).not.toHaveTextContent('ana-login');
    });

    it('says "(none)" for their queue when you picked a queue and they have none', async () => {
      const theirs = makeView({}, { assignee: 'bob-login', queue: null, version: 5 });
      const panel = await conflictOn(theirs, 'Edit Assignee', async () => {
        await waitFor(() => expect(screen.getByLabelText('Assignee').querySelector('option[value="queue:implementer"]')).not.toBeNull());
        fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: 'queue:implementer' } });
      });
      expect(panel).toHaveTextContent('(none)');
      expect(panel).toHaveTextContent('Queue · Implementer');
    });

    it('shows their queue by name when you picked a queue too', async () => {
      const theirs = makeView({}, { assignee: null, queue: 'reviewer', queueName: 'Reviewer', version: 5 });
      patchItem.mockRejectedValue(rejection(409, { current: theirs }));
      renderView(makeView({ viewerRole: 'lead' }));
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Edit Assignee' }));
      await waitFor(() => expect(screen.getByLabelText('Assignee').querySelector('option[value="queue:implementer"]')).not.toBeNull());
      fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: 'queue:implementer' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save Assignee' }));
      await screen.findByRole('alert');
      const panel = review();
      expect(panel).toHaveTextContent('Queue · Reviewer');
      expect(panel).toHaveTextContent('Queue · Implementer');
    });

    it('shows "(empty)" on both sides when you cleared a date and they have none either', async () => {
      const theirs = makeView({}, { dueOn: null, version: 5 });
      const panel = await conflictOn(theirs, 'Edit Due', () => fireEvent.change(screen.getByLabelText('Due'), { target: { value: '' } }));
      expect(within(panel).getAllByText('(empty)')).toHaveLength(2);
    });

    it('shows their date next to the one you typed', async () => {
      const theirs = makeView({}, { dueOn: '2027-01-02', version: 5 });
      const panel = await conflictOn(theirs, 'Edit Due', () => fireEvent.change(screen.getByLabelText('Due'), { target: { value: '2026-12-31' } }));
      expect(within(panel).getByText('2027-01-02')).toBeInTheDocument();
      expect(within(panel).getByText('2026-12-31')).toBeInTheDocument();
    });

    it('says "(none)" for their parent when they removed it', async () => {
      const theirs = makeView({ parentKey: null }, { version: 5 });
      const panel = await conflictOn(theirs, 'Edit Parent', () => fireEvent.change(screen.getByLabelText('Parent'), { target: { value: '' } }));
      expect(within(panel).getByText('(none)')).toBeInTheDocument();
    });

    it('works from the item the page holds when the refusal carries no current version', async () => {
      const panel = await conflictOn(undefined, 'Edit Due', () => fireEvent.change(screen.getByLabelText('Due'), { target: { value: '2026-12-31' } }));
      expect(within(panel).getByText('2026-12-24')).toBeInTheDocument();
      expect(within(panel).getByText('2026-12-31')).toBeInTheDocument();
    });

    it('lists the hand-off fields the person changed', async () => {
      handoffItem.mockRejectedValue(rejection(409, { current: makeView({}, { version: 5 }) }));
      renderView();
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Hand off' }));
      fireEvent.change(screen.getByLabelText('Status after handoff'), { target: { value: 'review' } });
      fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'ana-login' } });
      fireEvent.change(screen.getByLabelText('Handoff note'), { target: { value: 'n' } });
      fireEvent.click(screen.getByRole('button', { name: 'Send handoff' }));
      await screen.findByRole('alert');
      const panel = review();
      expect(panel).toHaveTextContent('Their version (status)');
      expect(panel).toHaveTextContent('Your unsaved text (assignee)');
    });

    it('uses the draft of a stale hand-off form as the text to compare', async () => {
      addComment.mockResolvedValue({ id: 9 });
      renderView();
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Hand off' }));
      fireEvent.change(screen.getByLabelText('Handoff note'), { target: { value: 'mid-sentence' } });
      getItem.mockResolvedValue(makeView({}, { version: 5, title: 'Their title' }));
      await comment();
      await screen.findByRole('alert');
      expect(review()).toHaveTextContent('mid-sentence');
    });
  });

  describe('keeping the editor open on top of their version', () => {
    it('refetches the item when the refusal carried no current version, then sends the new version', async () => {
      patchItem.mockRejectedValueOnce(rejection(409));
      renderView();
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Edit Priority' }));
      fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '1' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
      await screen.findByRole('alert');
      getItem.mockResolvedValue(makeView({}, { version: 7, reporterName: 'Cara' }));
      fireEvent.click(screen.getByRole('button', { name: 'Keep editing on top of theirs' }));
      await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
      patchItem.mockResolvedValue(makeView({}, { priority: 1, version: 8 }));
      fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
      await waitFor(() => expect(patchItem).toHaveBeenLastCalledWith('PLAT-7', { expectedVersion: 7, changes: { priority: 1 } }));
    });

    it('shows the failure when that refetch fails', async () => {
      patchItem.mockRejectedValueOnce(rejection(409));
      renderView();
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Edit Priority' }));
      fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '1' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
      await screen.findByRole('alert');
      getItem.mockRejectedValue(rejection(500, {}, 'server is down'));
      fireEvent.click(screen.getByRole('button', { name: 'Keep editing on top of theirs' }));
      await waitFor(() => expect(toast.error).toHaveBeenCalledWith('server is down'));
    });

    it('falls back to a generic message when that refetch fails without one', async () => {
      patchItem.mockRejectedValueOnce(rejection(409));
      renderView();
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Edit Priority' }));
      fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '1' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
      await screen.findByRole('alert');
      getItem.mockRejectedValue({ status: 500 });
      fireEvent.click(screen.getByRole('button', { name: 'Keep editing on top of theirs' }));
      await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Could not reload the item.'));
    });

    it('survives the editor being cancelled while the refetch is in flight', async () => {
      patchItem.mockRejectedValueOnce(rejection(409));
      renderView();
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Edit Priority' }));
      fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '1' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
      await screen.findByRole('alert');
      let finish;
      getItem.mockReturnValue(new Promise((r) => { finish = r; }));
      fireEvent.click(screen.getByRole('button', { name: 'Keep editing on top of theirs' }));
      fireEvent.click(screen.getByRole('button', { name: 'Cancel editing Priority' }));
      await act(async () => { finish(makeView({}, { version: 7 })); });
      expect(screen.getByRole('button', { name: 'Edit Priority' })).toBeEnabled();
      expect(screen.queryByRole('alert')).toBeNull();
    });
  });

  describe('loading', () => {
    it('shows a generic message when the load fails without one', async () => {
      getItem.mockRejectedValue({ status: 500 });
      render(<MemoryRouter initialEntries={['/work/item/PLAT-7']}><Routes><Route path="/work/item/:key" element={<WorkItemView />} /></Routes></MemoryRouter>);
      expect(await screen.findByText('Could not load the item.')).toBeInTheDocument();
    });

    it('ignores a failure of a load that a later save has already overtaken', async () => {
      addComment.mockResolvedValue({ id: 9 });
      renderView();
      await ready();
      let fail;
      getItem.mockReturnValue(new Promise((_r, reject) => { fail = reject; }));
      await comment();
      await waitFor(() => expect(getItem).toHaveBeenCalledTimes(2));
      patchItem.mockResolvedValue(makeView({}, { priority: 1, version: 5 }));
      fireEvent.click(screen.getByRole('button', { name: 'Edit Priority' }));
      fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '1' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
      expect(await screen.findByText('P1')).toBeInTheDocument();
      await act(async () => { fail(rejection(404, {}, 'gone')); });
      expect(screen.getByText('P1')).toBeInTheDocument();
      expect(screen.queryByText('No such item')).toBeNull();
    });

    it('copes with the lists of members, queues, products and parents coming back empty', async () => {
      listMembers.mockResolvedValue(null);
      listQueues.mockResolvedValue(null);
      listProducts.mockResolvedValue(null);
      listItems.mockResolvedValue(null);
      renderView(makeView({ viewerRole: 'lead', productKey: null, parentKey: null }));
      await ready();
      await waitFor(() => expect(listProducts).toHaveBeenCalled());
      fireEvent.click(screen.getByRole('button', { name: 'Edit Parent' }));
      expect(within(screen.getByLabelText('Parent')).getAllByRole('option').map((o) => o.textContent)).toEqual(['None']);
    });

    it('names the current assignee from the item when the member list failed to load', async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      listMembers.mockRejectedValue(new Error('no members'));
      addComment.mockResolvedValue({ id: 9 });
      renderView(makeView({ viewerRole: 'lead' }));
      await screen.findByRole('heading', { level: 1, name: 'Fix the flux capacitor' });
      await waitFor(() => expect(warn).toHaveBeenCalledWith('WorkItemView: could not load the project members', expect.any(Error)));
      fireEvent.click(screen.getByRole('button', { name: 'Edit Assignee' }));
      getItem.mockResolvedValue(makeView({ viewerRole: 'lead' }, { version: 5, title: 'Their title' }));
      await comment();
      await screen.findByRole('alert');
      expect(review()).toHaveTextContent('Bob Brown');
      warn.mockRestore();
    });
  });

  describe('refused writes', () => {
    it('falls back to a generic toast when the server gave no message', async () => {
      patchItem.mockRejectedValue({ status: 500 });
      renderView();
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Edit Priority' }));
      fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '1' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
      await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Could not save the change.'));
      expect(screen.getByLabelText('Priority').value).toBe('1');
    });

    it('shows the field error from the response body, or the error message when the body has none', async () => {
      patchItem.mockRejectedValue(rejection(400, { body: { field: 'dueOn' } }, 'Due date is not a date'));
      renderView();
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Edit Due' }));
      fireEvent.change(screen.getByLabelText('Due'), { target: { value: '2026-12-01' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save Due' }));
      expect(await screen.findByText('Due date is not a date')).toBeInTheDocument();
      expect(screen.getByLabelText('Due').value).toBe('2026-12-01');
    });

    it('a hand-off field error shows in the hand-off form, not beside the Status field', async () => {
      handoffItem.mockRejectedValue(rejection(400, { body: { field: 'status', message: 'Cannot hand off to done' } }));
      renderView();
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Hand off' }));
      fireEvent.change(screen.getByLabelText('Status after handoff'), { target: { value: 'review' } });
      fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'ana-login' } });
      fireEvent.change(screen.getByLabelText('Handoff note'), { target: { value: 'n' } });
      fireEvent.click(screen.getByRole('button', { name: 'Send handoff' }));
      expect(await screen.findByText('Cannot hand off to done')).toBeInTheDocument();
      expect(screen.getAllByText('Cannot hand off to done')).toHaveLength(1);
    });
  });

  describe('after the project is archived under an open editor', () => {
    const archivedRefusal = rejection(409, { reason: 'archived' }, 'This project is archived.');
    const refuse = async () => {
      patchItem.mockRejectedValueOnce(archivedRefusal);
      renderView();
      await ready();
      getItem.mockResolvedValue(makeView({ archived: true }, { version: 9 }));
      fireEvent.click(screen.getByRole('button', { name: 'Edit Priority' }));
      fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '1' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
      await waitFor(() => expect(toast.info).toHaveBeenCalledWith('This project is archived; nothing was changed.'));
      await screen.findByText('This project is archived. It is read-only.');
    };

    it('closes the editor that was open, leaving no Save button that can only be refused again', async () => {
      await refuse();
      expect(screen.queryByLabelText('Priority')).toBeNull();
      expect(screen.queryByRole('button', { name: 'Save Priority' })).toBeNull();
      expect(screen.getByText('P3')).toBeInTheDocument();
    });
  });

  describe('claims held by an unnamed agent', () => {
    const CLAIM = {
      assignee: null, assigneeName: null, queue: 'implementer', queueName: 'Implementer', status: 'in_progress',
      claimedByAgent: 3, claimedByName: null, claimedAt: '2026-11-16T13:05:00Z', claimExpiresAt: '2099-11-16T15:40:00Z',
    };

    it('says "An agent\'s claim was released" when a change ends it', async () => {
      patchItem.mockResolvedValue(makeView({}, { ...CLAIM, claimedByAgent: null, claimedByName: null, priority: 1, version: 5 }));
      renderView(makeView({}, CLAIM));
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Edit Priority' }));
      fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '1' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
      await waitFor(() => expect(toast.info).toHaveBeenCalledWith("An agent's claim was released."));
    });

    it('says "Released an agent\'s claim" after a person releases it', async () => {
      releaseItem.mockResolvedValue({});
      renderView(makeView({}, CLAIM));
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Release claim' }));
      fireEvent.click(screen.getByRole('button', { name: 'Release it' }));
      await waitFor(() => expect(toast.success).toHaveBeenCalledWith("Released an agent's claim"));
    });

    it('warns beside Status that moving the item releases the claim of "An agent"', async () => {
      renderView(makeView({}, CLAIM));
      await ready();
      expect(screen.getByText('An agent is working on this; moving it releases its claim.')).toBeInTheDocument();
    });
  });

  describe('displaying values the page does not know', () => {
    it('shows an unknown status, autonomy and done-means as they are, and a missing reporter as Unknown', async () => {
      renderView(makeView({}, { status: 'weird', autonomy: 'odd', doneMeans: 'strange', level: 'epic', reporterName: null, createdAt: null, updatedAt: null }));
      await ready();
      expect(screen.getByText('weird')).toBeInTheDocument();
      expect(screen.getByText('odd')).toBeInTheDocument();
      expect(screen.getByText('strange')).toBeInTheDocument();
      expect(screen.getByText('Unknown')).toBeInTheDocument();
      expect(screen.getByText('Created').nextSibling).toHaveTextContent('');
    });

    it('shows no parent, product or kind as None, None and software', async () => {
      renderView(makeView({ parentKey: null, productKey: null }, { kind: null }));
      await ready();
      expect(screen.getAllByText('None')).toHaveLength(2);
      expect(screen.getByText('software')).toBeInTheDocument();
    });

    it('offers a product with no name by its key', async () => {
      listProducts.mockResolvedValue([{ key: 'bare' }]);
      renderView(makeView({ productKey: null, viewerRole: 'lead' }));
      await ready();
      await waitFor(() => expect(listProducts).toHaveBeenCalled());
      fireEvent.click(screen.getByRole('button', { name: 'Edit Product' }));
      await waitFor(() => expect(within(screen.getByLabelText('Product')).getByRole('option', { name: 'bare' })).toBeInTheDocument());
    });

    it('an epic with no derived products shows no Products row', async () => {
      listItems.mockResolvedValue([]);
      renderView(makeView({ derivedProductKeys: undefined, parentKey: null }, { key: 'PLAT-1', level: 'epic', title: 'Big epic' }), '/work/item/PLAT-1');
      await screen.findByRole('heading', { level: 1, name: 'Big epic' });
      expect(screen.queryByText('Products', { selector: 'dt' })).toBeNull();
    });

    it('an epic with derived products lists them', async () => {
      listItems.mockResolvedValue([]);
      renderView(makeView({ derivedProductKeys: ['wikantik', 'docs'], parentKey: null }, { key: 'PLAT-1', level: 'epic', title: 'Big epic' }), '/work/item/PLAT-1');
      await screen.findByRole('heading', { level: 1, name: 'Big epic' });
      expect(screen.getByText('Products', { selector: 'dt' })).toBeInTheDocument();
      expect(screen.getByText('wikantik, docs')).toBeInTheDocument();
    });
  });

  describe('the Allow direct policy', () => {
    it.each([
      ['Yes', 'true', null, true],
      ['No', 'false', null, false],
      ['Inherited', '', true, null],
    ])('a lead choosing %s sends %s as %s', async (_label, choice, before, sent) => {
      patchItem.mockResolvedValue(makeView({ viewerRole: 'lead' }, { allowDirect: sent, version: 5 }));
      renderView(makeView({ viewerRole: 'lead' }, { allowDirect: before }));
      await ready();
      fireEvent.click(screen.getByRole('button', { name: 'Edit Direct commits by agents' }));
      fireEvent.change(screen.getByLabelText('Direct commits by agents'), { target: { value: choice } });
      fireEvent.click(screen.getByRole('button', { name: 'Save Direct commits by agents' }));
      await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-7', { expectedVersion: 4, changes: { allowDirect: sent } }));
    });
  });
});
