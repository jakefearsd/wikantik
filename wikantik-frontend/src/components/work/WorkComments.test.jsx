import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';

vi.mock('../../api/client', () => ({ api: { listMentionableUsers: vi.fn() } }));
import { api } from '../../api/client';
import WorkComments from './WorkComments';

const MEMBERS = [
  { principal: 'Ana Alvarez', principalKind: 'user', role: 'lead', displayName: 'Ana Alvarez', mentionId: 'u-ana' },
  { principal: 'Bob Brown', principalKind: 'user', role: 'member', displayName: 'Bob Brown', mentionId: 'u-bob' },
  { principal: 'No Id', principalKind: 'user', role: 'member', displayName: 'No Id' },
  { principal: 'ops', principalKind: 'group', role: 'member', displayName: 'ops' },
];
const esc = (t) => t.replace(/&/g, '&amp;').replace(/</g, '&lt;');
const c = (id, mine, body, extra = {}) => ({
  id, itemId: 7, author: mine ? 'Ana Alvarez' : 'Bob Brown', authorName: mine ? 'Ana Alvarez' : 'Bob Brown', mine, body,
  bodyHtml: body ? `<p>${esc(body)}</p>` : '', mentions: [],
  createdAt: '2026-11-01T10:00:00Z', editedAt: null, removedAt: null, ...extra,
});
const handlers = () => ({
  onAdd: vi.fn().mockResolvedValue(true), onEdit: vi.fn().mockResolvedValue(true), onRemove: vi.fn().mockResolvedValue(true),
});
const setup = (props = {}) => {
  const h = handlers();
  render(<WorkComments comments={[]} members={MEMBERS} canComment isLead={false} {...h} {...props} />);
  return h;
};

beforeEach(() => vi.clearAllMocks());

describe('WorkComments', () => {
  it('mentionPickerOffersProjectMembersOnly: users with a mention id from the project member list, never the wiki directory', async () => {
    setup();
    const box = screen.getByLabelText('Add a comment');
    box.focus();
    fireEvent.change(box, { target: { value: '@' } });
    const list = await screen.findByRole('listbox', { name: 'User mentions' });
    const names = within(list).getAllByRole('option').map((o) => o.textContent);
    expect(names).toEqual(['@Ana Alvarez', '@Bob Brown']);
    expect(api.listMentionableUsers).not.toHaveBeenCalled();
    expect(screen.queryByText(/ops/)).toBeNull();
  });

  it('the picker inserts the wiki mention token and the text is sent as typed', async () => {
    const { onAdd } = setup();
    const box = screen.getByLabelText('Add a comment');
    box.focus();
    fireEvent.change(box, { target: { value: 'hi @Bo', selectionStart: 6 } });
    fireEvent.mouseDown(await screen.findByRole('option', { name: '@Bob Brown' }));
    expect(box.value).toBe('hi @[Bob Brown](user:u-bob) ');
    fireEvent.change(box, { target: { value: 'hi @[Bob Brown](user:u-bob) please look' } });
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    expect(onAdd).toHaveBeenCalledWith('hi @[Bob Brown](user:u-bob) please look');
  });

  it('shows the server-rendered comment and marks its mentions without injecting HTML', () => {
    setup({ comments: [c(1, false, 'x', {
      bodyHtml: '<p><strong>bold</strong> ping @Ana Alvarez and @stranger</p><pre><code>@Ana Alvarez</code></pre>',
      mentions: ['Ana Alvarez', '<img src=x onerror=alert(1)>'],
    })] });
    expect(screen.getByText('bold').tagName).toBe('STRONG');
    const chips = document.querySelectorAll('.comment-mention-chip');
    expect(chips).toHaveLength(1);
    expect(chips[0].textContent).toBe('@Ana Alvarez');
    expect(document.querySelector('img')).toBeNull();
    expect(screen.getByText(/@stranger/)).toBeInTheDocument();
  });

  it('removedCommentShowsMarker', () => {
    setup({ comments: [c(1, false, '', { removedAt: '2026-11-02T10:00:00Z' }), c(2, false, 'kept')] });
    expect(screen.getByText('Comment removed')).toBeInTheDocument();
    expect(screen.getByText('kept')).toBeInTheDocument();
  });

  it('onlyAuthorSeesEdit', () => {
    setup({ comments: [c(1, true, 'mine'), c(2, false, 'theirs')] });
    const edits = screen.getAllByRole('button', { name: /^Edit comment/ });
    expect(edits).toHaveLength(1);
    expect(edits[0]).toHaveAccessibleName('Edit comment by Ana Alvarez');
  });

  it('a lead may remove anyone\'s comment but not edit it', () => {
    setup({ isLead: true, comments: [c(2, false, 'theirs')] });
    expect(screen.queryByRole('button', { name: /^Edit comment/ })).toBeNull();
    expect(screen.getByRole('button', { name: 'Remove comment by Bob Brown' })).toBeInTheDocument();
  });

  it('a plain member may remove only their own comment', () => {
    setup({ comments: [c(1, true, 'mine'), c(2, false, 'theirs')] });
    expect(screen.getAllByRole('button', { name: /^Remove comment/ })).toHaveLength(1);
  });

  it('edits the comment text as stored, tokens and all', () => {
    const { onEdit } = setup({ comments: [c(1, true, 'hello @[Bob Brown](user:u-bob)')] });
    fireEvent.click(screen.getByRole('button', { name: 'Edit comment by Ana Alvarez' }));
    const box = screen.getByLabelText('Edit your comment');
    expect(box.value).toBe('hello @[Bob Brown](user:u-bob)');
    fireEvent.change(box, { target: { value: 'hello @[Bob Brown](user:u-bob), updated' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save comment' }));
    expect(onEdit).toHaveBeenCalledWith(1, 'hello @[Bob Brown](user:u-bob), updated');
  });

  it('confirms inline before removing', async () => {
    const { onRemove } = setup({ comments: [c(1, true, 'mine')] });
    fireEvent.click(screen.getByRole('button', { name: 'Remove comment by Ana Alvarez' }));
    expect(onRemove).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove' }));
    await waitFor(() => expect(onRemove).toHaveBeenCalledWith(1));
  });

  it('can cancel a removal', () => {
    const { onRemove } = setup({ comments: [c(1, true, 'mine')] });
    fireEvent.click(screen.getByRole('button', { name: 'Remove comment by Ana Alvarez' }));
    fireEvent.click(screen.getByRole('button', { name: 'Keep' }));
    expect(onRemove).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Remove comment by Ana Alvarez' })).toBeInTheDocument();
  });

  it('marks an edited comment', () => {
    setup({ comments: [c(1, false, 'x', { editedAt: '2026-11-02T10:00:00Z' })] });
    expect(screen.getByText('(edited)')).toBeInTheDocument();
  });

  it('keeps the typed comment when posting fails and clears it when it works', async () => {
    const h = handlers();
    h.onAdd.mockResolvedValueOnce(false).mockResolvedValueOnce(true);
    render(<WorkComments comments={[]} members={MEMBERS} canComment isLead={false} {...h} />);
    const box = screen.getByLabelText('Add a comment');
    fireEvent.change(box, { target: { value: 'draft text' } });
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    await waitFor(() => expect(h.onAdd).toHaveBeenCalledTimes(1));
    expect(box.value).toBe('draft text');
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    await waitFor(() => expect(box.value).toBe(''));
  });

  it('viewers get no comment box and no controls', () => {
    setup({ canComment: false, comments: [c(1, true, 'mine')] });
    expect(screen.queryByLabelText('Add a comment')).toBeNull();
    expect(screen.queryByRole('button', { name: /comment/i })).toBeNull();
    expect(screen.getByText('mine')).toBeInTheDocument();
  });

  it('says when there are no comments', () => {
    setup();
    expect(screen.getByText('No comments yet.')).toBeInTheDocument();
  });

  it('does not post a blank comment', () => {
    const { onAdd } = setup();
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    expect(onAdd).not.toHaveBeenCalled();
  });
});
