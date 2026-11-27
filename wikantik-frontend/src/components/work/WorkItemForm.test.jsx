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
const openMore = () => fireEvent.click(screen.getByRole('button', { name: /More options/ }));
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
    openMore();
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
    openMore();
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
    openMore();
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

  describe('edges', () => {
    const MEMBERS = [{ principal: 'bob-login', principalKind: 'user', role: 'member', displayName: 'Bob Brown' }];
    const QUEUES = [{ key: 'implementer', name: 'Implementer', retiredAt: null }];
    const submitWith = async (extra) => {
      createItem.mockResolvedValue({ item: { id: 9, key: 'PLAT-9', status: 'draft' } });
      fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'New thing' } });
      if (extra) extra();
      fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
      await waitFor(() => expect(createItem).toHaveBeenCalled());
      return createItem.mock.calls[0][0];
    };

    it('still lets the person create an item when the parent candidates cannot be loaded', async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      listItems.mockRejectedValue(new Error('down'));
      setup();
      await waitFor(() => expect(warn).toHaveBeenCalledWith('WorkItemForm: failed to load parent candidates', expect.any(Error)));
      expect(parentOptions()).toEqual(['No parent']);
      const draft = await submitWith();
      expect(draft).toMatchObject({ title: 'New thing', level: 'task' });
      expect(draft).not.toHaveProperty('parentKey');
      warn.mockRestore();
    });

    it('treats an empty candidate answer as no candidates', async () => {
      listItems.mockResolvedValue(null);
      setup();
      await waitFor(() => expect(listItems).toHaveBeenCalled());
      expect(parentOptions()).toEqual(['No parent']);
    });

    it('does not touch the form when the candidates arrive after it was closed', async () => {
      let resolve;
      listItems.mockReturnValue(new Promise((r) => { resolve = r; }));
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const { unmount } = render(<WorkItemForm isOpen project={PROJECT} onClose={vi.fn()} />);
      unmount();
      resolve(ITEMS);
      await Promise.resolve();
      expect(warn).not.toHaveBeenCalled();
      warn.mockRestore();
    });

    it('asks only for open statuses of this project when loading parent candidates', async () => {
      setup();
      await waitFor(() => expect(listItems).toHaveBeenCalledWith({
        project: 'PLAT', status: ['draft', 'refining', 'ready', 'in_progress', 'review'], limit: 200,
      }));
    });

    it('sends nothing when the form is submitted with a blank title', async () => {
      setup();
      await waitFor(() => expect(listItems).toHaveBeenCalled());
      fireEvent.change(screen.getByLabelText('Title'), { target: { value: '  ' } });
      fireEvent.submit(screen.getByRole('button', { name: 'Create item' }).closest('form'));
      expect(createItem).not.toHaveBeenCalled();
    });

    it('sends the due date, kind, priority and description that were chosen', async () => {
      setup();
      await waitFor(() => expect(listItems).toHaveBeenCalled());
      const draft = await submitWith(() => {
        openMore();
        fireEvent.change(screen.getByLabelText('Due date'), { target: { value: '2026-12-01' } });
        fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'docs' } });
        fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '1' } });
        fireEvent.change(screen.getByLabelText('Description'), { target: { value: 'Some **text**' } });
      });
      expect(draft).toMatchObject({ dueOn: '2026-12-01', kind: 'docs', priority: 1, body: 'Some **text**' });
    });

    it('leaves out the due date, parent and assignee when none were chosen', async () => {
      setup();
      await waitFor(() => expect(listItems).toHaveBeenCalled());
      const draft = await submitWith();
      for (const k of ['dueOn', 'parentKey', 'queue', 'assignee']) expect(draft).not.toHaveProperty(k);
      expect(draft).toMatchObject({ kind: 'software', priority: 3, body: '' });
    });

    it('drops a chosen product and parent when the level is switched to epic', async () => {
      setup({ products: PRODUCTS });
      await waitFor(() => expect(parentOptions().join()).toContain('PLAT-2'));
      openMore();
      fireEvent.change(screen.getByLabelText('Product'), { target: { value: 'wikantik' } });
      fireEvent.click(screen.getByLabelText('Epic'));
      const draft = await submitWith();
      expect(draft).toMatchObject({ level: 'epic' });
      expect(draft).not.toHaveProperty('productKey');
      expect(draft).not.toHaveProperty('parentKey');
    });

    it('forgets the chosen parent when the level changes', async () => {
      setup();
      await waitFor(() => expect(parentOptions().join()).toContain('PLAT-2'));
      fireEvent.change(screen.getByLabelText('Parent'), { target: { value: 'PLAT-2' } });
      fireEvent.click(screen.getByLabelText('Story'));
      const draft = await submitWith();
      expect(draft).toMatchObject({ level: 'story' });
      expect(draft).not.toHaveProperty('parentKey');
    });

    it('shows a product without a name by its key', () => {
      setup({ products: [{ key: 'bare' }] });
      openMore();
      expect(within(screen.getByLabelText('Product')).getByRole('option', { name: 'bare' })).toBeInTheDocument();
    });

    it('sends no assignee once Unassigned is chosen again after a person', async () => {
      setup({ members: MEMBERS, queues: QUEUES });
      await waitFor(() => expect(listItems).toHaveBeenCalled());
      const draft = await submitWith(() => {
        fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: 'bob-login' } });
        fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: '' } });
      });
      expect(draft).not.toHaveProperty('assignee');
      expect(draft).not.toHaveProperty('queue');
    });

    it('sends no queue once Unassigned is chosen again after a queue', async () => {
      setup({ members: MEMBERS, queues: QUEUES });
      await waitFor(() => expect(listItems).toHaveBeenCalled());
      const draft = await submitWith(() => {
        fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: 'queue:implementer' } });
        fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: '' } });
      });
      expect(draft).not.toHaveProperty('queue');
    });

    it('falls back to a generic message when the failure carries none', async () => {
      createItem.mockRejectedValue({});
      setup();
      await waitFor(() => expect(listItems).toHaveBeenCalled());
      fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'x' } });
      fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Could not create the item.');
      expect(screen.getByRole('button', { name: 'Create item' })).toBeEnabled();
    });

    it('disables the button and sends once while the creation is in flight', async () => {
      createItem.mockReturnValue(new Promise(() => {}));
      setup();
      await waitFor(() => expect(listItems).toHaveBeenCalled());
      fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'x' } });
      const form = screen.getByRole('button', { name: 'Create item' }).closest('form');
      fireEvent.submit(form);
      await waitFor(() => expect(screen.getByRole('button', { name: 'Create item' })).toBeDisabled());
      fireEvent.submit(form);
      expect(createItem).toHaveBeenCalledTimes(1);
    });

    it('closes without creating anything on Cancel', async () => {
      const { onClose } = setup();
      await waitFor(() => expect(listItems).toHaveBeenCalled());
      fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
      expect(onClose).toHaveBeenCalled();
      expect(createItem).not.toHaveBeenCalled();
    });

    it('closes without needing an onCreated handler', async () => {
      createItem.mockResolvedValue({ item: { id: 9, key: 'PLAT-9' } });
      const onClose = vi.fn();
      render(<WorkItemForm isOpen project={PROJECT} onClose={onClose} />);
      fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'x' } });
      fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
      await waitFor(() => expect(onClose).toHaveBeenCalled());
    });

    it('renders no form while closed and does not load candidates', () => {
      render(<WorkItemForm isOpen={false} project={PROJECT} onClose={vi.fn()} />);
      expect(screen.queryByLabelText('Title')).not.toBeInTheDocument();
      expect(listItems).not.toHaveBeenCalled();
    });
  });

  const fillTitle = (t = 'Ship it') => fireEvent.change(screen.getByLabelText('Title'), { target: { value: t } });
  const addLabel = (name) => {
    fireEvent.change(screen.getByLabelText('Add a label'), { target: { value: name } });
    fireEvent.keyDown(screen.getByLabelText('Add a label'), { key: 'Enter' });
  };

  it('sends type and labels', async () => {
    createItem.mockResolvedValue({ item: { id: 9, key: 'PLAT-9' } });
    setup();
    fillTitle();
    openMore();
    fireEvent.change(screen.getByLabelText('Type'), { target: { value: 'bug' } });
    addLabel('UI');
    addLabel('api');
    fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
    await waitFor(() => expect(createItem).toHaveBeenCalled());
    expect(createItem).toHaveBeenCalledWith(expect.objectContaining({ type: 'bug', labels: ['api', 'ui'] }));
    await waitFor(() => expect(listItems).toHaveBeenCalled());
  });

  it('sends a label typed but not yet added', async () => {
    createItem.mockResolvedValue({ item: { id: 9, key: 'PLAT-9' } });
    setup();
    fillTitle();
    openMore();
    addLabel('ui');
    fireEvent.change(screen.getByLabelText('Add a label'), { target: { value: 'api' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
    await waitFor(() => expect(createItem).toHaveBeenCalled());
    expect(createItem).toHaveBeenCalledWith(expect.objectContaining({ labels: ['api', 'ui'] }));
  });

  it('an epic has no type control and sends no type', async () => {
    createItem.mockResolvedValue({ item: { id: 9, key: 'PLAT-9' } });
    setup();
    fireEvent.click(screen.getByLabelText('Epic'));
    openMore();
    expect(screen.queryByLabelText('Type')).not.toBeInTheDocument();
    fillTitle();
    fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
    await waitFor(() => expect(createItem).toHaveBeenCalled());
    expect(createItem.mock.calls[0][0]).not.toHaveProperty('type');
    await waitFor(() => expect(listItems).toHaveBeenCalled());
  });

  it('sends no labels when none were added', async () => {
    createItem.mockResolvedValue({ item: { id: 9, key: 'PLAT-9' } });
    setup();
    fillTitle();
    fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
    await waitFor(() => expect(createItem).toHaveBeenCalled());
    expect(createItem.mock.calls[0][0]).not.toHaveProperty('labels');
    expect(createItem.mock.calls[0][0]).toHaveProperty('type', 'feature');
    await waitFor(() => expect(listItems).toHaveBeenCalled());
  });
});

describe('WorkItemForm product default and More options', () => {
  const TWO = [{ key: 'web', name: 'Web', archived: false }, { key: 'api', name: 'Api', archived: false }];
  const submitted = async () => {
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Ship it' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
    await waitFor(() => expect(createItem).toHaveBeenCalled());
    return createItem.mock.calls[0][0];
  };
  beforeEach(() => createItem.mockResolvedValue({ item: { id: 9, key: 'PLAT-9' } }));

  it('pre-selects the project default product', async () => {
    setup({ project: { ...PROJECT, defaultProductKey: 'web' }, products: TWO });
    expect((await submitted()).productKey).toBe('web');
  });

  it('pre-selects the only active product when there is no default', async () => {
    setup({ products: [{ key: 'web', name: 'Web' }, { key: 'old', name: 'Old', archived: true }] });
    expect((await submitted()).productKey).toBe('web');
  });

  it('does not pre-select an archived default or one of several products, and lets the server decide', async () => {
    setup({ project: { ...PROJECT, defaultProductKey: 'old' }, products: [...TWO, { key: 'old', name: 'Old', archived: true }] });
    expect(await submitted()).not.toHaveProperty('productKey');
  });

  it('picks up the project default when the products arrive after the form opened', async () => {
    const project = { ...PROJECT, defaultProductKey: 'web' };
    const { rerender } = render(<WorkItemForm isOpen project={project} products={[]} onClose={vi.fn()} />);
    rerender(<WorkItemForm isOpen project={project} products={TWO} onClose={vi.fn()} />);
    expect((await submitted()).productKey).toBe('web');
  });

  it('sends no productKey when the products never load, so the server default applies', async () => {
    setup({ project: { ...PROJECT, defaultProductKey: 'web' }, products: [] });
    expect(await submitted()).not.toHaveProperty('productKey');
  });

  it('sends an empty productKey when the user picks No product in the main form', async () => {
    setup({ products: TWO });
    fireEvent.change(screen.getByLabelText('Product'), { target: { value: 'web' } });
    fireEvent.change(screen.getByLabelText('Product'), { target: { value: '' } });
    expect((await submitted()).productKey).toBe('');
  });

  it('keeps an explicit No product when the late products bring a default', async () => {
    const project = { ...PROJECT, defaultProductKey: 'web' };
    const { rerender } = render(<WorkItemForm isOpen project={project} products={[{ key: 'api', name: 'Api' }]} onClose={vi.fn()} />);
    openMore();
    fireEvent.change(screen.getByLabelText('Product'), { target: { value: '' } });
    rerender(<WorkItemForm isOpen project={project} products={TWO} onClose={vi.fn()} />);
    expect((await submitted()).productKey).toBe('');
  });

  it('keeps the chosen product across a switch to Epic and back', async () => {
    setup({ project: { ...PROJECT, defaultProductKey: 'web' }, products: TWO });
    openMore();
    fireEvent.change(screen.getByLabelText('Product'), { target: { value: 'api' } });
    fireEvent.click(screen.getByLabelText('Epic'));
    fireEvent.click(screen.getByLabelText('Task'));
    expect((await submitted()).productKey).toBe('api');
  });

  it('keeps an explicit No product across a switch to Epic and back', async () => {
    setup({ project: { ...PROJECT, defaultProductKey: 'web' }, products: TWO });
    openMore();
    fireEvent.change(screen.getByLabelText('Product'), { target: { value: '' } });
    fireEvent.click(screen.getByLabelText('Epic'));
    fireEvent.click(screen.getByLabelText('Story'));
    expect((await submitted()).productKey).toBe('');
  });

  it('names the labels and the due date in the summary so nothing hidden submits unseen', () => {
    setup({ project: { ...PROJECT, defaultProductKey: 'web' }, products: TWO });
    openMore();
    const toggle = () => screen.getByRole('button', { name: /More options/ });
    expect(toggle()).not.toHaveTextContent('label');
    expect(toggle()).not.toHaveTextContent('Due');
    fireEvent.change(screen.getByLabelText('Add a label'), { target: { value: 'ui' } });
    fireEvent.keyDown(screen.getByLabelText('Add a label'), { key: 'Enter' });
    expect(toggle()).toHaveTextContent('Product: Web · Feature · Software · P3 · 1 label');
    fireEvent.change(screen.getByLabelText('Add a label'), { target: { value: 'api' } });
    fireEvent.keyDown(screen.getByLabelText('Add a label'), { key: 'Enter' });
    fireEvent.change(screen.getByLabelText('Due date'), { target: { value: '2026-12-01' } });
    expect(toggle()).toHaveTextContent('Product: Web · Feature · Software · P3 · 2 labels · Due 2026-12-01');
  });

  it('shows Product outside More options only when none was pre-selected', () => {
    setup({ products: TWO });
    expect(screen.getByLabelText('Product')).toBeInTheDocument();
  });

  it('sends an empty productKey when the user chooses No product', async () => {
    setup({ project: { ...PROJECT, defaultProductKey: 'web' }, products: TWO });
    openMore();
    fireEvent.change(screen.getByLabelText('Product'), { target: { value: '' } });
    expect((await submitted()).productKey).toBe('');
  });

  it('omits productKey for an epic', async () => {
    setup({ project: { ...PROJECT, defaultProductKey: 'web' }, products: TWO });
    fireEvent.click(screen.getByLabelText('Epic'));
    expect(await submitted()).not.toHaveProperty('productKey');
  });

  it('keeps Type, Kind, Priority, Due date and Labels under More options, collapsed', () => {
    setup({ project: { ...PROJECT, defaultProductKey: 'web' }, products: TWO });
    const toggle = screen.getByRole('button', { name: /More options/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(toggle).toHaveTextContent('More options (Product: Web · Feature · Software · P3)');
    for (const l of ['Priority', 'Kind', 'Type', 'Due date', 'Add a label']) expect(screen.queryByLabelText(l)).toBeNull();
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    for (const l of ['Priority', 'Kind', 'Type', 'Due date', 'Add a label']) expect(screen.getByLabelText(l)).toBeInTheDocument();
  });

  it('updates the summary live', () => {
    setup({ project: { ...PROJECT, defaultProductKey: 'web' }, products: TWO });
    openMore();
    fireEvent.change(screen.getByLabelText('Type'), { target: { value: 'bug' } });
    fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '1' } });
    expect(screen.getByRole('button', { name: /More options/ })).toHaveTextContent('Product: Web · Bug · Software · P1');
  });

  it('explains Type and Kind', () => {
    setup();
    openMore();
    expect(screen.getByText('What kind of work: feature, bug or chore')).toBeInTheDocument();
    expect(screen.getByText('Which area: software, docs or admin')).toBeInTheDocument();
  });

  it('submits the same defaults whether More options was opened or not', async () => {
    setup({ project: { ...PROJECT, defaultProductKey: 'web' }, products: TWO });
    const closed = await submitted();
    expect(closed).toMatchObject({ kind: 'software', type: 'feature', priority: 3, productKey: 'web' });
    createItem.mockClear();
    openMore();
    expect(await submitted()).toEqual(closed);
  });
});
