import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  getProjectFlow: vi.fn(), getProjectDora: vi.fn(), listLabels: vi.fn(), listProducts: vi.fn(), getVelocity: vi.fn(),
  getSprintReport: vi.fn(),
}));
import { getProjectFlow, getProjectDora, listLabels, listProducts, getVelocity, getSprintReport } from '../../api/work';
vi.mock('./useWorkProject', () => ({ useWorkProject: vi.fn() }));
import { useWorkProject } from './useWorkProject';
import WorkFlow from './WorkFlow';
import { badAttributes } from './charts/chartAttrs';

const dur = (n, p50, p85) => ({ n, p50Seconds: p50, p85Seconds: p85 });
const FLOW = {
  from: '2026-11-02', to: '2026-11-15', zone: 'Europe/Berlin',
  throughput: [{ weekStart: '2026-11-02', items: 0, points: 0 }, { weekStart: '2026-11-09', items: 2, points: 4.5 }],
  cycleTime: dur(2, 691200, 700000), leadTime: dur(2, 950400, 950400),
  wip: [
    { status: 'in_progress', count: 3, items: [{ key: 'PLAT-1', title: 'A title', ageSeconds: 5400 }] },
    { status: 'review', count: 0, items: [] },
  ],
};
const DORA = {
  from: '2026-11-02', to: '2026-11-15', zone: 'Europe/Berlin', product: null,
  deploymentFrequency: [{ weekStart: '2026-11-02', deployments: 1 }, { weekStart: '2026-11-09', deployments: 0 }],
  leadTimeForChanges: dur(0, null, null), changesWithoutCommitTime: 0, changesWithClampedCommitTime: 0, maxCommitLeadDays: 30,
  changeFailureRate: { numerator: 0, denominator: 1 }, recoveryTime: dur(0, null, null), unrecovered: 0,
  reworkRate: { numerator: 0, denominator: 1 }, merges: { reviewed: 0, unreviewed: 0, direct: 0, flagged: 0, flaggedUnacknowledged: 0 },
};
const Where = () => <div data-testid="where">{useLocation().search}</div>;
const renderAt = (url = '/work/PLAT/flow') => render(
  <MemoryRouter initialEntries={[url]}>
    <Routes><Route path="/work/:project/flow" element={<><WorkFlow /><Where /></>} /></Routes>
  </MemoryRouter>);

beforeEach(() => {
  vi.clearAllMocks();
  useWorkProject.mockReturnValue({ project: { key: 'PLAT' }, role: 'member', sprints: [] });
  getProjectFlow.mockResolvedValue(FLOW);
  getProjectDora.mockResolvedValue(DORA);
  listLabels.mockResolvedValue([{ name: 'backend', items: 3 }]);
  listProducts.mockResolvedValue([{ key: 'wikantik', name: 'Wikantik' }]);
  getVelocity.mockResolvedValue({ sprints: [] });
});

describe('WorkFlow', () => {
  it('requests the flow with the filters from the URL', async () => {
    renderAt('/work/PLAT/flow?from=2026-11-02&to=2026-11-15&label=backend&type=bug&assignee=Ana&sprint=4');
    await screen.findByRole('region', { name: 'Throughput' });
    expect(getProjectFlow).toHaveBeenCalledWith('PLAT',
      { from: '2026-11-02', to: '2026-11-15', label: 'backend', type: 'bug', assignee: 'Ana', sprint: '4' });
    await waitFor(() => expect(getProjectDora).toHaveBeenCalledWith('PLAT', { from: '2026-11-02', to: '2026-11-15' }));
    expect(screen.getByLabelText('From')).toHaveValue('2026-11-02');
  });

  it('puts the applied period in the URL and does not request an invalid one', async () => {
    renderAt();
    await screen.findByRole('region', { name: 'Throughput' });
    getProjectFlow.mockClear();
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-11-10' } });
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-11-01' } });
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }));
    expect(screen.getByRole('alert')).toHaveTextContent('The start date is after the end date.');
    expect(getProjectFlow).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-11-20' } });
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('?from=2026-11-10&to=2026-11-20'));
    await waitFor(() => expect(getProjectFlow).toHaveBeenCalledWith('PLAT', { from: '2026-11-10', to: '2026-11-20' }));
  });

  it('refuses a period over 366 days before asking', async () => {
    renderAt('/work/PLAT/flow?from=2024-01-01&to=2026-01-01');
    expect(await screen.findByRole('alert')).toHaveTextContent('at most 366 days');
    expect(getProjectFlow).not.toHaveBeenCalled();
    expect(getProjectDora).not.toHaveBeenCalled();
  });

  it('mutes partial weeks', async () => {
    getProjectFlow.mockResolvedValue({ ...FLOW, from: '2026-11-04' });
    const { container } = renderAt('/work/PLAT/flow?from=2026-11-04&to=2026-11-15');
    await screen.findByRole('region', { name: 'Throughput' });
    const bars = container.querySelectorAll('section[aria-label="Throughput"] rect.work-chart-bar');
    expect(bars).toHaveLength(2); // a zero week is still a 1px mark
    expect(bars[0].getAttribute('data-muted')).toBe('true'); // the period starts mid-week
    expect(bars[1].getAttribute('data-muted')).toBeNull();
  });

  it('states the throughput definition and how it differs from velocity', async () => {
    renderAt();
    const t = await screen.findByRole('region', { name: 'Throughput' });
    expect(t).toHaveTextContent('will not match a sprint\'s velocity');
    expect(t).toHaveTextContent('2 items, 4.5 points in total');
    expect(t).toHaveTextContent('Europe/Berlin');
  });

  it('describes cycle and lead time with p50 and p85 as plain sentences, sample size and window', async () => {
    renderAt();
    const c = await screen.findByRole('region', { name: 'Cycle time' });
    expect(within(c).getByText('Half of items took less than').nextSibling).toHaveTextContent('8 d');
    expect(c).toHaveTextContent('85% of items took less than');
    expect(c).toHaveTextContent('from 2 items');
    expect(c).toHaveTextContent('few data points');
    expect(c).toHaveTextContent('Nov 2 2026 to Nov 15 2026');
  });

  it('says so, with no chart or zero, when nothing was completed', async () => {
    getProjectFlow.mockResolvedValue({
      ...FLOW, throughput: [{ weekStart: '2026-11-02', items: 0, points: 0 }], cycleTime: dur(0, null, null), leadTime: dur(0, null, null),
    });
    const { container } = renderAt();
    const t = await screen.findByRole('region', { name: 'Throughput' });
    expect(t).toHaveTextContent('No items were completed in this period.');
    expect(screen.getByRole('region', { name: 'Cycle time' })).toHaveTextContent('No items were completed in this period.');
    expect(container.querySelector('section[aria-label="Throughput"] svg')).toBeNull();
    expect(container.textContent).not.toMatch(/NaN|Infinity/);
  });

  it('lists work in progress with item links, ages and a count beyond the shown list', async () => {
    renderAt();
    const w = await screen.findByRole('region', { name: 'In progress now' });
    expect(within(w).getByRole('link', { name: 'PLAT-1' })).toHaveAttribute('href', '/work/item/PLAT-1');
    expect(w).toHaveTextContent('2 h old'); // 5400 s through formatDuration
  });

  it('shows the oldest-first note when more are in progress than listed', async () => {
    renderAt();
    expect(await screen.findByText('Showing the oldest 1 of 3.')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'In review now' })).toHaveTextContent('Nothing is in review right now.');
  });

  it('has no chart attributes that are NaN or Infinity', async () => {
    const { container } = renderAt();
    await screen.findByRole('region', { name: 'Throughput' });
    expect(badAttributes(container)).toEqual([]);
  });

  it('hides the sprint section for a project with no sprint', async () => {
    renderAt();
    await screen.findByRole('region', { name: 'Throughput' });
    expect(screen.queryByRole('region', { name: 'Sprint velocity' })).toBeNull();
    expect(getVelocity).not.toHaveBeenCalled();
    expect(screen.queryByLabelText('Filter by sprint')).toBeNull();
  });

  it('shows sprint velocity, labelled apart from throughput, and the chosen sprint burndown', async () => {
    useWorkProject.mockReturnValue({ project: { key: 'PLAT' }, role: 'member', sprints: [{ id: 4, name: 'Sprint 4', state: 'active' }] });
    getVelocity.mockResolvedValue({ sprints: [{ id: 3, name: 'Sprint 3', completedPoints: 8 }] });
    getSprintReport.mockResolvedValue({
      sprint: { id: 4, startsOn: '2026-11-02', endsOn: '2026-11-15', committed: { items: 2, points: 5 } },
      days: [{ date: '2026-11-02', remainingPoints: 5, scopePoints: 5 }],
    });
    renderAt('/work/PLAT/flow?sprint=4');
    const v = await screen.findByRole('region', { name: 'Sprint velocity' });
    expect(v).toHaveTextContent('snapshot at sprint end');
    expect(await screen.findByRole('region', { name: 'Sprint burndown' })).toBeInTheDocument();
    expect(getSprintReport).toHaveBeenCalledWith('4');
    expect(screen.getByLabelText('Filter by sprint')).toHaveValue('4');
  });

  it('rejects a sprint that is not a number without asking', async () => {
    renderAt('/work/PLAT/flow?sprint=abc');
    expect(await screen.findByRole('alert')).toHaveTextContent('A sprint is named by its number.');
    expect(getProjectFlow).not.toHaveBeenCalled();
  });

  it('a 404 shows Not found, not an error banner', async () => {
    getProjectFlow.mockRejectedValue(Object.assign(new Error('gone'), { status: 404 }));
    renderAt();
    expect(await screen.findByText('Not found')).toBeInTheDocument();
    expect(screen.queryByText('gone')).toBeNull();
  });

  it('a failed load shows an inline error with retry; a failed reload keeps the last good figures', async () => {
    renderAt();
    await screen.findByRole('region', { name: 'Throughput' });
    getProjectFlow.mockRejectedValueOnce(Object.assign(new Error('boom'), { status: 500 }));
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-11-01' } });
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByText(/boom/)).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Throughput' })).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole('button', { name: 'Retry' })[0]);
    await waitFor(() => expect(screen.queryByText(/boom/)).toBeNull());
  });

  it('shows the delivery panel on its own and narrows it by product', async () => {
    getProjectFlow.mockReturnValue(new Promise(() => {})); // the flow never answers: delivery still loads
    renderAt();
    expect(await screen.findByRole('region', { name: 'Deployment frequency' })).toBeInTheDocument();
    fireEvent.change(await screen.findByLabelText('Delivery product'), { target: { value: 'wikantik' } });
    await waitFor(() => expect(getProjectDora).toHaveBeenLastCalledWith('PLAT', { product: 'wikantik' }));
    expect(screen.getByTestId('where')).toHaveTextContent('product=wikantik');
  });

  it('shows Not found inline when the product is unknown, leaving the flow figures', async () => {
    getProjectDora.mockRejectedValue(Object.assign(new Error('No such product'), { status: 404 }));
    renderAt();
    await screen.findByRole('region', { name: 'Throughput' });
    expect(await screen.findByText('Not found')).toBeInTheDocument();
    expect(screen.queryByText(/No such product/)).toBeNull();
  });
});
