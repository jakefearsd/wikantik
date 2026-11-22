import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  addReportedComment: vi.fn(), markItemRead: vi.fn(() => Promise.resolve()), getWatch: vi.fn(() => Promise.resolve({ state: 'watching' })),
  setWatch: vi.fn(), listAttachments: vi.fn(() => Promise.resolve({ attachments: [], mayAttach: true, maxFileBytes: 1e7, maxItemBytes: 5e7, usedBytes: 0 })),
  uploadAttachment: vi.fn(), removeAttachment: vi.fn(), attachmentUrl: (k, id) => `/a/${k}/${id}`,
  listMembers: vi.fn(), getItem: vi.fn(), listEvents: vi.fn(),
}));
vi.mock('../../hooks/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }) }));
import { addReportedComment, markItemRead, listMembers, listEvents } from '../../api/work';
import WorkReportedItem from './WorkReportedItem';

const VIEW = {
  key: 'PLAT-4', projectKey: 'PLAT', projectName: 'Platform', level: 'story', type: 'bug', status: 'in_progress',
  title: 'Login fails', body: 'It fails', bodyHtml: '<p>It fails</p>', reporter: 'rita-login', reporterName: 'Rita R',
  createdAt: '2026-11-01T10:00:00Z', updatedAt: '2026-11-02T10:00:00Z', archived: false,
  comments: [{ id: 1, authorName: 'Lena L', mine: false, body: 'Looking', bodyHtml: '<p>Looking</p>', mentions: [], createdAt: '2026-11-02T09:00:00Z' },
    { id: 2, authorName: 'Rita R', mine: true, body: 'thanks', bodyHtml: '<p>thanks</p>', mentions: [], createdAt: '2026-11-02T09:30:00Z' }],
  events: [
    { id: 1, type: 'created', actorName: 'Rita R', at: '2026-11-01T10:00:00Z', note: null },
    { id: 2, type: 'status', actorName: 'Lena L', toValue: 'in_progress', fromValue: 'draft', assignee: 'bob-login', queue: 'impl', note: 'hidden reason', at: '2026-11-02T08:00:00Z' },
  ],
};
const setup = (view = VIEW, onReload = vi.fn(() => Promise.resolve())) => {
  render(<MemoryRouter><WorkReportedItem view={view} onReload={onReload} /></MemoryRouter>);
  return onReload;
};

beforeEach(() => { vi.clearAllMocks(); });

describe('WorkReportedItem', () => {
  it('shows the status in plain words, the text, the discussion and the history lines', async () => {
    setup();
    expect(screen.getByRole('heading', { name: 'Login fails' })).toBeInTheDocument();
    expect(screen.getByText('In progress', { selector: '.badge, span' })).toBeInTheDocument();
    expect(screen.getByText('It fails')).toBeInTheDocument();
    expect(screen.getByText('Looking')).toBeInTheDocument();
    expect(screen.getByText('Lena L moved this to In progress')).toBeInTheDocument();
    expect(screen.getByText('Rita R filed this')).toBeInTheDocument();
    await waitFor(() => expect(markItemRead).toHaveBeenCalledWith('PLAT-4'));
  });

  it('shows no assignee, links, changes or sprint, and renders nothing the event carried beyond the line', () => {
    const { container } = render(<MemoryRouter><WorkReportedItem view={VIEW} onReload={vi.fn()} /></MemoryRouter>);
    const text = container.textContent;
    for (const word of ['Assignee', 'Sprint', 'Estimate', 'Labels', 'Parent', 'Links', 'Changes', 'Deployment', 'Priority', 'hidden reason', 'bob-login', 'impl']) {
      expect(text).not.toContain(word);
    }
    expect(container.querySelector('a[href^="/work/PLAT"]')).toBeNull();
    expect(screen.queryByRole('tab')).toBeNull();
    expect(screen.queryByRole('combobox')).toBeNull();
  });

  it('adding a comment calls addReportedComment and reloads', async () => {
    addReportedComment.mockResolvedValue({});
    const onReload = setup();
    fireEvent.change(screen.getByLabelText('Add a comment'), { target: { value: 'More detail' } });
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    await waitFor(() => expect(addReportedComment).toHaveBeenCalledWith('PLAT-4', 'More detail'));
    await waitFor(() => expect(onReload).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByLabelText('Add a comment')).toHaveValue(''));
  });

  it('an event with no acting person reads without an invented actor', () => {
    setup({ ...VIEW, events: [
      { id: 3, type: 'status', actor: null, actorName: null, actorKind: null, fromValue: null, toValue: 'ready', at: '2026-11-03T08:00:00Z' },
      { id: 4, type: 'status', actor: null, actorName: null, actorKind: 'system', fromValue: 'ready', toValue: 'draft', at: '2026-11-03T09:00:00Z' },
    ] });
    expect(screen.getAllByText('This moved to Ready')).toHaveLength(1);
    expect(screen.getByText('This moved to Draft')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Someone|The code host|An agent/);
  });

  it('blocks a comment over 20,000 characters and says so', () => {
    setup();
    fireEvent.change(screen.getByLabelText('Add a comment'), { target: { value: 'c'.repeat(20001) } });
    expect(screen.getByRole('alert')).toHaveTextContent('over the 20,000-character limit');
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    expect(addReportedComment).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Comment' })).toBeDisabled();
  });

  it('offers no edit or remove on comments, even their own', () => {
    setup();
    expect(screen.queryByRole('button', { name: /Edit comment/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Remove comment/ })).toBeNull();
  });

  it('has no mention list and never asks for the members', async () => {
    setup();
    const box = screen.getByLabelText('Add a comment');
    box.focus();
    fireEvent.change(box, { target: { value: '@' } });
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(listMembers).not.toHaveBeenCalled();
    expect(listEvents).not.toHaveBeenCalled();
  });

  it('an archived item has no comment box', () => {
    setup({ ...VIEW, archived: true });
    expect(screen.queryByLabelText('Add a comment')).toBeNull();
  });
});
