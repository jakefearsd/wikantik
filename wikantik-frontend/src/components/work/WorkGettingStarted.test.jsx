import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  listProducts: vi.fn(), listQueues: vi.fn(), listAgents: vi.fn(), createQueue: vi.fn(),
}));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import { listProducts, listQueues, listAgents, createQueue } from '../../api/work';
import WorkGettingStarted from './WorkGettingStarted';

const KEY = 'wikantik.work.gettingStarted.dismissed';
const onCreateProject = vi.fn();

function renderCard(props = {}) {
  return render(
    <MemoryRouter>
      <WorkGettingStarted isAdmin projects={[]} onCreateProject={onCreateProject} {...props} />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  try { window.localStorage.clear(); } catch { /* ignore */ }
  listProducts.mockResolvedValue([]);
  listQueues.mockResolvedValue([]);
  listAgents.mockResolvedValue([]);
  createQueue.mockResolvedValue({});
});
afterEach(() => vi.restoreAllMocks());

describe('WorkGettingStarted', () => {
  it('shows admins each step with its real state', async () => {
    listProducts.mockResolvedValue([{ key: 'web', archived: false }]);
    listQueues.mockResolvedValue([{ key: 'old', retiredAt: '2026-01-01' }]);
    renderCard();
    expect(await screen.findByText('Getting started')).toBeInTheDocument();
    expect(screen.getByTestId('step-product')).toHaveTextContent('Done');
    expect(screen.getByTestId('step-project')).toHaveTextContent('Not yet');
    expect(screen.getByTestId('step-queues')).toHaveTextContent('Not yet');
    expect(screen.getByTestId('step-agent')).toHaveTextContent('Not yet');
    fireEvent.click(screen.getByRole('button', { name: 'Create a project' }));
    expect(onCreateProject).toHaveBeenCalled();
    expect(screen.getByRole('link', { name: 'Register an agent' })).toHaveAttribute('href', '/work/agents');
    expect(screen.getByText(/new epics and agent-created items start as drafts/i)).toBeInTheDocument();
  });

  it('offers Create starter queues when there is no active queue', async () => {
    listQueues.mockResolvedValue([{ key: 'review', retiredAt: '2026-01-01' }]);
    renderCard();
    fireEvent.click(await screen.findByRole('button', { name: 'Create starter queues' }));
    await waitFor(() => expect(createQueue).toHaveBeenCalledTimes(1));
    expect(createQueue.mock.calls[0][0].key).toBe('implementer');
    await waitFor(() => expect(listQueues).toHaveBeenCalledTimes(2));
  });

  it('renders nothing for a non-admin with no project and loads nothing (WorkHome\'s empty state says who to ask)', async () => {
    const { container } = renderCard({ isAdmin: false });
    await Promise.resolve();
    expect(container).toBeEmptyDOMElement();
    expect(listProducts).not.toHaveBeenCalled();
    expect(listQueues).not.toHaveBeenCalled();
    expect(listAgents).not.toHaveBeenCalled();
  });

  it('renders nothing for a non-admin who has a project', async () => {
    const { container } = renderCard({ isAdmin: false, projects: [{ key: 'P', archived: false }] });
    await Promise.resolve();
    expect(container).toBeEmptyDOMElement();
  });

  it('stays hidden after Dismiss and survives storage that throws', async () => {
    const first = renderCard();
    fireEvent.click(await screen.findByRole('button', { name: 'Dismiss' }));
    expect(screen.queryByText('Getting started')).toBeNull();
    expect(window.localStorage.getItem(KEY)).toBeTruthy();
    first.unmount();
    renderCard();
    await Promise.resolve();
    expect(screen.queryByText('Getting started')).toBeNull();

    window.localStorage.clear();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const real = Object.getOwnPropertyDescriptor(window, 'localStorage');
    Object.defineProperty(window, 'localStorage', { configurable: true, get() { throw new Error('denied'); } });
    try {
      renderCard();
      expect(await screen.findByText('Getting started')).toBeInTheDocument();
      expect(warn).toHaveBeenCalled();
      fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));
      expect(screen.queryByText('Getting started')).toBeNull();
    } finally {
      if (real) Object.defineProperty(window, 'localStorage', real);
    }
  });

  it('renders nothing when a load fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    listQueues.mockRejectedValue(new Error('down'));
    const { container } = renderCard();
    await waitFor(() => expect(warn).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('still shows an admin with a project and product but no queue or agent', async () => {
    listProducts.mockResolvedValue([{ key: 'web', archived: false }]);
    renderCard({ projects: [{ key: 'P', archived: false }] });
    expect(await screen.findByText('Getting started')).toBeInTheDocument();
    expect(screen.getByTestId('step-queues')).toHaveTextContent('Not yet');
    expect(screen.getByRole('button', { name: 'Create starter queues' })).toBeInTheDocument();
  });

  it('is hidden for an admin who has everything set up', async () => {
    listProducts.mockResolvedValue([{ key: 'web', archived: false }]);
    listQueues.mockResolvedValue([{ key: 'review', retiredAt: null }]);
    listAgents.mockResolvedValue([{ name: 'a', retiredAt: null }]);
    const { container } = renderCard({ projects: [{ key: 'P', archived: false }] });
    await waitFor(() => expect(listAgents).toHaveBeenCalled());
    await Promise.resolve();
    expect(container).toBeEmptyDOMElement();
  });
});
