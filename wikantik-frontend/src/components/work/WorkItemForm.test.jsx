import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';

vi.mock('../../api/work', () => ({ listItems: vi.fn(), createItem: vi.fn() }));
import { listItems, createItem } from '../../api/work';
import WorkItemForm from './WorkItemForm';

const PROJECT = { id: 1, key: 'PLAT', name: 'Platform' };
const PRODUCTS = [{ id: 3, key: 'wikantik', name: 'Wikantik' }];
const ITEMS = [
  { id: 1, key: 'PLAT-1', level: 'epic', title: 'Epic one' },
  { id: 2, key: 'PLAT-2', level: 'story', title: 'Story two' },
  { id: 3, key: 'PLAT-3', level: 'task', title: 'Task three' },
];

function setup(props = {}) {
  const onCreated = vi.fn();
  const onClose = vi.fn();
  render(<WorkItemForm isOpen project={PROJECT} products={PRODUCTS} onCreated={onCreated} onClose={onClose} {...props} />);
  return { onCreated, onClose };
}
const parentOptions = () => within(screen.getByLabelText('Parent')).getAllByRole('option').map((o) => o.textContent);

beforeEach(() => {
  vi.clearAllMocks();
  listItems.mockResolvedValue(ITEMS);
});

describe('WorkItemForm', () => {
  it('restricts parent options by level: task to stories, story to epics, epic to none', async () => {
    setup();
    await waitFor(() => expect(parentOptions().join()).toContain('PLAT-2'));
    expect(parentOptions().join()).not.toContain('PLAT-1');
    expect(parentOptions().join()).not.toContain('PLAT-3');

    fireEvent.click(screen.getByLabelText('Story'));
    expect(parentOptions().join()).toContain('PLAT-1');
    expect(parentOptions().join()).not.toContain('PLAT-2');

    fireEvent.click(screen.getByLabelText('Epic'));
    expect(screen.queryByLabelText('Parent')).not.toBeInTheDocument();
  });

  it('hides the product choice for an epic', async () => {
    setup();
    expect(screen.getByLabelText('Product')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('Epic'));
    expect(screen.queryByLabelText('Product')).not.toBeInTheDocument();
    await waitFor(() => expect(listItems).toHaveBeenCalled());
  });

  it('keeps submit disabled while the title is blank', async () => {
    setup();
    const submit = screen.getByRole('button', { name: 'Create item' });
    expect(submit).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: '   ' } });
    expect(submit).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Ship it' } });
    expect(submit).toBeEnabled();
    await waitFor(() => expect(listItems).toHaveBeenCalled());
  });

  it('creates the item and reports it without closing on error', async () => {
    const view = { item: { id: 9, key: 'PLAT-9', status: 'draft' }, productKey: 'wikantik' };
    createItem.mockResolvedValue(view);
    const { onCreated, onClose } = setup();
    await waitFor(() => expect(parentOptions().join()).toContain('PLAT-2'));
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: ' Ship it ' } });
    fireEvent.change(screen.getByLabelText('Parent'), { target: { value: 'PLAT-2' } });
    fireEvent.change(screen.getByLabelText('Product'), { target: { value: 'wikantik' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(view.item, view));
    expect(createItem).toHaveBeenCalledWith(expect.objectContaining({
      project: 'PLAT', level: 'task', title: 'Ship it', parentKey: 'PLAT-2', productKey: 'wikantik',
    }));
    expect(onClose).toHaveBeenCalled();
  });

  it('shows the server message and stays open when creation fails', async () => {
    createItem.mockRejectedValue(Object.assign(new Error('title is too long'), { status: 400 }));
    const { onCreated, onClose } = setup();
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'x' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('title is too long');
    expect(onCreated).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('does not offer an archived product', () => {
    setup({ products: [{ key: 'wikantik', name: 'Wikantik', archived: false }, { key: 'old', name: 'Old', archived: true }] });
    const select = screen.getByLabelText('Product');
    expect(within(select).getByRole('option', { name: 'Wikantik' })).toBeInTheDocument();
    expect(within(select).queryByRole('option', { name: 'Old' })).toBeNull();
  });

  describe('assignment', () => {
    const MEMBERS = [{ principal: 'bob-login', principalKind: 'user', role: 'member', displayName: 'Bob Brown' }];
    const QUEUES = [{ key: 'implementer', name: 'Implementer', retiredAt: null }];
    const create = async (choose) => {
      createItem.mockResolvedValue({ item: { id: 9, key: 'PLAT-9', status: 'draft' } });
      setup({ members: MEMBERS, queues: QUEUES });
      fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'New thing' } });
      if (choose) fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: choose } });
      fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
      await waitFor(() => expect(createItem).toHaveBeenCalled());
      return createItem.mock.calls[0][0];
    };

    it('createWithQueueSendsQueue and no assignee', async () => {
      const draft = await create('queue:implementer');
      expect(draft.queue).toBe('implementer');
      expect(draft).not.toHaveProperty('assignee');
    });

    it('creating for a person sends the assignee and no queue', async () => {
      const draft = await create('bob-login');
      expect(draft.assignee).toBe('bob-login');
      expect(draft).not.toHaveProperty('queue');
    });

    it('switching from a queue to a person sends only the person', async () => {
      createItem.mockResolvedValue({ item: { id: 9, key: 'PLAT-9', status: 'draft' } });
      setup({ members: MEMBERS, queues: QUEUES });
      fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'T' } });
      fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: 'queue:implementer' } });
      fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: 'bob-login' } });
      fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
      await waitFor(() => expect(createItem).toHaveBeenCalled());
      const draft = createItem.mock.calls[0][0];
      expect(draft).toHaveProperty('assignee', 'bob-login');
      expect(draft).not.toHaveProperty('queue');
    });

    it('leaving it unassigned sends neither', async () => {
      const draft = await create(null);
      expect(draft).not.toHaveProperty('assignee');
      expect(draft).not.toHaveProperty('queue');
    });
  });
});
