import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';

vi.mock('../../api/client', () => ({ api: { listMentionableUsers: vi.fn() } }));
vi.mock('../../api/work', () => ({ uploadAttachment: vi.fn() }));
import { api } from '../../api/client';
import { uploadAttachment } from '../../api/work';
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

  const openPicker = async (box, value = '@') => {
    box.focus();
    fireEvent.change(box, { target: { value, selectionStart: value.length } });
    await screen.findByRole('option', { name: '@Ana Alvarez' });
  };

  it('Enter in the open picker inserts the highlighted member instead of submitting', async () => {
    const { onAdd } = setup();
    const box = screen.getByLabelText('Add a comment');
    await openPicker(box);
    fireEvent.keyDown(box, { key: 'Enter' });
    expect(box.value).toBe('@[Ana Alvarez](user:u-ana) ');
    expect(onAdd).not.toHaveBeenCalled();
  });

  it('arrow keys move the highlight and Tab inserts the chosen member', async () => {
    setup();
    const box = screen.getByLabelText('Add a comment');
    await openPicker(box);
    fireEvent.keyDown(box, { key: 'ArrowDown' });
    fireEvent.keyDown(box, { key: 'Tab' });
    expect(box.value).toBe('@[Bob Brown](user:u-bob) ');
  });

  it('Escape closes the picker without inserting anything', async () => {
    setup();
    const box = screen.getByLabelText('Add a comment');
    await openPicker(box);
    fireEvent.keyDown(box, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull());
    expect(box.value).toBe('@');
  });

  it('Enter with no matching member inserts nothing', async () => {
    setup();
    const box = screen.getByLabelText('Add a comment');
    box.focus();
    fireEvent.change(box, { target: { value: '@zzz', selectionStart: 4 } });
    await new Promise((r) => setTimeout(r, 250));
    fireEvent.keyDown(box, { key: 'Enter' });
    expect(box.value).toBe('@zzz');
  });

  it('Ctrl+Enter posts the new comment and Cmd+Enter saves an edit', async () => {
    const { onAdd, onEdit } = setup({ comments: [c(1, true, 'old')] });
    const box = screen.getByLabelText('Add a comment');
    fireEvent.change(box, { target: { value: 'quick one' } });
    fireEvent.keyDown(box, { key: 'Enter', ctrlKey: true });
    await waitFor(() => expect(onAdd).toHaveBeenCalledWith('quick one'));
    fireEvent.click(screen.getByRole('button', { name: 'Edit comment by Ana Alvarez' }));
    const edit = screen.getByLabelText('Edit your comment');
    fireEvent.change(edit, { target: { value: '  new text  ' } });
    fireEvent.keyDown(edit, { key: 'Enter', metaKey: true });
    await waitFor(() => expect(onEdit).toHaveBeenCalledWith(1, 'new text'));
    await waitFor(() => expect(screen.queryByLabelText('Edit your comment')).toBeNull());
  });

  it('a plain Enter in the comment box does not post', () => {
    const { onAdd } = setup();
    const box = screen.getByLabelText('Add a comment');
    fireEvent.change(box, { target: { value: 'text' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    expect(onAdd).not.toHaveBeenCalled();
  });

  it('does not save a blank edit and Cancel leaves the comment unchanged', () => {
    const { onEdit } = setup({ comments: [c(1, true, 'old')] });
    fireEvent.click(screen.getByRole('button', { name: 'Edit comment by Ana Alvarez' }));
    fireEvent.change(screen.getByLabelText('Edit your comment'), { target: { value: '   ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save comment' }));
    expect(onEdit).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByLabelText('Edit your comment')).toBeNull();
    expect(screen.getByText('old')).toBeInTheDocument();
  });

  it('starts an edit with an empty box when the comment carries no source text', () => {
    setup({ comments: [c(1, true, undefined, { bodyHtml: '<p>rendered</p>', createdAt: null })] });
    fireEvent.click(screen.getByRole('button', { name: 'Edit comment by Ana Alvarez' }));
    expect(screen.getByLabelText('Edit your comment').value).toBe('');
  });

  it('keeps the edit open and logs when saving an edit fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const h = handlers();
    h.onEdit.mockRejectedValue(new Error('boom'));
    render(<WorkComments comments={[c(1, true, 'old')]} members={MEMBERS} canComment isLead={false} {...h} />);
    fireEvent.click(screen.getByRole('button', { name: 'Edit comment by Ana Alvarez' }));
    fireEvent.change(screen.getByLabelText('Edit your comment'), { target: { value: 'changed' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save comment' }));
    await waitFor(() => expect(warn).toHaveBeenCalledWith('WorkComments: comment action failed', expect.any(Error)));
    expect(screen.getByLabelText('Edit your comment').value).toBe('changed');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save comment' })).not.toBeDisabled());
    warn.mockRestore();
  });

  it('keeps the removal prompt and logs when removing fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const h = handlers();
    h.onRemove.mockRejectedValue(new Error('boom'));
    render(<WorkComments comments={[c(1, true, 'mine')]} members={MEMBERS} canComment isLead={false} {...h} />);
    fireEvent.click(screen.getByRole('button', { name: 'Remove comment by Ana Alvarez' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove' }));
    await waitFor(() => expect(warn).toHaveBeenCalled());
    expect(screen.getByText('Remove this comment?')).toBeInTheDocument();
    warn.mockRestore();
  });

  it('keeps the typed text and logs when posting throws', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const h = handlers();
    h.onAdd.mockRejectedValue(new Error('boom'));
    render(<WorkComments comments={[]} members={MEMBERS} canComment isLead={false} {...h} />);
    const box = screen.getByLabelText('Add a comment');
    fireEvent.change(box, { target: { value: 'keep me' } });
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    await waitFor(() => expect(warn).toHaveBeenCalledWith('WorkComments: posting failed', expect.any(Error)));
    expect(box.value).toBe('keep me');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Comment' })).not.toBeDisabled());
    warn.mockRestore();
  });

  describe('a failed post, edit or remove', () => {
    beforeEach(() => vi.spyOn(console, 'warn').mockImplementation(() => {}));

    it('post: shows the server message beside the box, keeps the text, clears when they type', async () => {
      const { onAdd } = setup();
      onAdd.mockRejectedValue(new Error('That comment is too long.'));
      const box = screen.getByLabelText('Add a comment');
      fireEvent.change(box, { target: { value: 'my words' } });
      fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('That comment is too long.');
      expect(box.value).toBe('my words');
      fireEvent.change(box, { target: { value: 'my words, shorter' } });
      expect(screen.queryByRole('alert')).toBeNull();
    });

    it('post: says a plain default when the error has no message, and clears on the next success', async () => {
      const { onAdd } = setup();
      onAdd.mockRejectedValueOnce({});
      const box = screen.getByLabelText('Add a comment');
      fireEvent.change(box, { target: { value: 'hello' } });
      fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Could not save the comment. Try again.');
      fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
      await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
      expect(box.value).toBe('');
    });

    it('edit: shows the message in the editor and keeps the edited text', async () => {
      const { onEdit } = setup({ comments: [c(1, true, 'first words')] });
      onEdit.mockRejectedValue(new Error('Comments on a closed item are locked.'));
      fireEvent.click(screen.getByRole('button', { name: 'Edit comment by Ana Alvarez' }));
      const box = screen.getByLabelText('Edit your comment');
      fireEvent.change(box, { target: { value: 'better words' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save comment' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Comments on a closed item are locked.');
      expect(screen.getByLabelText('Edit your comment').value).toBe('better words');
      fireEvent.change(screen.getByLabelText('Edit your comment'), { target: { value: 'better words!' } });
      expect(screen.queryByRole('alert')).toBeNull();
    });

    it('edit: falls back to a plain default', async () => {
      const { onEdit } = setup({ comments: [c(1, true, 'first words')] });
      onEdit.mockRejectedValue({});
      fireEvent.click(screen.getByRole('button', { name: 'Edit comment by Ana Alvarez' }));
      fireEvent.change(screen.getByLabelText('Edit your comment'), { target: { value: 'x' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save comment' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Could not save the comment. Try again.');
    });

    it('remove: says so next to the confirmation, and the message goes when they keep the comment', async () => {
      const { onRemove } = setup({ comments: [c(1, true, 'first words')] });
      onRemove.mockRejectedValue({});
      fireEvent.click(screen.getByRole('button', { name: 'Remove comment by Ana Alvarez' }));
      fireEvent.click(screen.getByRole('button', { name: 'Yes, remove' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Could not remove the comment. Try again.');
      fireEvent.click(screen.getByRole('button', { name: 'Keep' }));
      expect(screen.queryByRole('alert')).toBeNull();
    });

    it('remove: shows the server message when there is one', async () => {
      const { onRemove } = setup({ comments: [c(1, true, 'first words')] });
      onRemove.mockRejectedValue(new Error('Only a lead may remove this.'));
      fireEvent.click(screen.getByRole('button', { name: 'Remove comment by Ana Alvarez' }));
      fireEvent.click(screen.getByRole('button', { name: 'Yes, remove' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Only a lead may remove this.');
    });
  });

  const ATTACH = { itemKey: 'PLAT-1', toast: { error: vi.fn() } };
  const imageClip = () => ({ files: [new File(['x'], 'image.png', { type: 'image/png' })], getData: () => '' });

  it('pastingIntoTheNewCommentUploadsToTheItemOnly', async () => {
    uploadAttachment.mockResolvedValue({ id: 4, markdown: '![s](work-attachment:4)' });
    setup({ attach: ATTACH });
    fireEvent.paste(screen.getByLabelText('Add a comment'), { clipboardData: imageClip() });
    await waitFor(() => expect(screen.getByLabelText('Add a comment')).toHaveValue('![s](work-attachment:4)'));
    expect(uploadAttachment.mock.calls[0][0]).toBe('PLAT-1');
    expect(uploadAttachment.mock.calls[0][2]).toBeNull();
  });

  it('pastingWhileEditingACommentSendsItsId', async () => {
    uploadAttachment.mockResolvedValue({ id: 4, markdown: '![s](work-attachment:4)' });
    setup({ attach: ATTACH, comments: [c(12, true, 'hello')] });
    fireEvent.click(screen.getByRole('button', { name: 'Edit comment by Ana Alvarez' }));
    fireEvent.paste(screen.getByLabelText('Edit your comment'), { clipboardData: imageClip() });
    await waitFor(() => expect(screen.getByLabelText('Edit your comment').value).toContain('![s](work-attachment:4)'));
    expect(uploadAttachment.mock.calls[0][2]).toBe(12);
  });

  it('withoutAttachAPasteIsNotIntercepted', () => {
    setup();
    fireEvent.paste(screen.getByLabelText('Add a comment'), { clipboardData: imageClip() });
    expect(uploadAttachment).not.toHaveBeenCalled();
  });
});
