import { describe, it, expect } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import DoraPanel from './DoraPanel';
import { badAttributes } from './charts/chartAttrs';

const dur = (n, p50, p85) => ({ n, p50Seconds: p50, p85Seconds: p85 });
const report = (extra = {}) => ({
  from: '2026-11-02', to: '2026-11-15', zone: 'UTC', product: null,
  deploymentFrequency: [{ weekStart: '2026-11-02', deployments: 4 }, { weekStart: '2026-11-09', deployments: 2 }],
  leadTimeForChanges: dur(7, 93600, 187200), changesWithoutCommitTime: 0, changesWithoutUsableCommitTime: 0, changesWithClampedCommitTime: 0,
  maxCommitLeadDays: 30, changeFailureRate: { numerator: 4, denominator: 7 }, recoveryTime: dur(3, 14400, 91800),
  unrecovered: 0, reworkRate: { numerator: 1, denominator: 7 },
  merges: { reviewed: 1, unreviewed: 1, direct: 1, flagged: 2, flaggedUnacknowledged: 1 }, ...extra,
});
const EMPTY = report({
  deploymentFrequency: [{ weekStart: '2026-11-02', deployments: 0 }, { weekStart: '2026-11-09', deployments: 0 }],
  leadTimeForChanges: dur(0, null, null), changeFailureRate: { numerator: 0, denominator: 0 },
  recoveryTime: dur(0, null, null), reworkRate: { numerator: 0, denominator: 0 },
  merges: { reviewed: 0, unreviewed: 0, direct: 0, flagged: 0, flaggedUnacknowledged: 0 },
});
const section = (name) => screen.getByRole('region', { name });

describe('DoraPanel', () => {
  it('states each figure with its definition, sample size, window and unit', () => {
    const { container } = render(<DoraPanel report={report()} today="2026-12-01" />);
    const lead = section('Lead time for changes');
    expect(lead).toHaveTextContent('How long a merged change took from its first commit');
    expect(within(lead).getByText('Half of changes took less than').nextSibling).toHaveTextContent('1 d 2 h');
    expect(within(lead).getByText('85% of changes took less than').nextSibling).toHaveTextContent('2 d 4 h');
    expect(lead).toHaveTextContent('from 7 changes');
    expect(lead).toHaveTextContent('Nov 2 2026 to Nov 15 2026 (UTC)');
    expect(section('Change failure rate')).toHaveTextContent('57%');
    expect(section('Change failure rate')).toHaveTextContent('4 of 7 deployments');
    expect(section('Deployment frequency')).toHaveTextContent('6 deployments in total');
    expect(section('Recovery time')).toHaveTextContent('from 3 recovered failures');
    expect(section('Rework rate')).toHaveTextContent('1 of 7 deployments');
    expect(badAttributes(container)).toEqual([]);
  });

  it('says there is nothing, never zero or NaN, for an empty window', () => {
    const { container } = render(<DoraPanel report={EMPTY} today="2026-12-01" />);
    expect(section('Deployment frequency')).toHaveTextContent('No deployments in this period.');
    expect(section('Lead time for changes')).toHaveTextContent('No changes shipped in this period.');
    expect(section('Change failure rate')).toHaveTextContent('No deployments in this period.');
    expect(section('Change failure rate')).not.toHaveTextContent('0%');
    expect(section('Recovery time')).toHaveTextContent('No failed deployments in this period.');
    expect(section('Rework rate')).not.toHaveTextContent('0%');
    expect(section('How changes were merged')).toHaveTextContent('No merges in this period.');
    expect(container.querySelector('svg')).toBeNull();
    expect(container.textContent).not.toMatch(/NaN|Infinity|undefined|null/);
    expect(badAttributes(container)).toEqual([]);
  });

  it('flags a small sample and a single value instead of hiding them', () => {
    render(<DoraPanel report={report({ leadTimeForChanges: dur(1, 7200, 7200), recoveryTime: dur(4, 3600, 7200) })} />);
    const lead = section('Lead time for changes');
    expect(lead).toHaveTextContent('from 1 change');
    expect(lead).toHaveTextContent('few data points');
    expect(lead).toHaveTextContent('with one value both figures are that value');
    expect(section('Recovery time')).toHaveTextContent('few data points');
  });

  it('names the changes with no commit time and the ones whose commit time was capped, using the server number of days', () => {
    render(<DoraPanel report={report({ changesWithoutCommitTime: 2, changesWithoutUsableCommitTime: 3, changesWithClampedCommitTime: 3, maxCommitLeadDays: 14 })} />);
    expect(screen.getByText('3 changes had no usable first-commit time (missing, or later than opening); their lead time starts when they were opened.')).toBeInTheDocument();
    expect(screen.queryByText(/had no commit time/)).toBeNull();
    expect(screen.getByText('3 changes had a first commit more than 14 days before they were opened; their lead time starts 14 days before opening.')).toBeInTheDocument();
  });

  it('shows neither note when no change fell back', () => {
    render(<DoraPanel report={report()} />);
    expect(screen.queryByText(/no usable first-commit/)).toBeNull();
    expect(screen.queryByText(/first commit more than/)).toBeNull();
  });

  it('names the unrecovered failures and keeps them out of the durations', () => {
    render(<DoraPanel report={report({ unrecovered: 2 })} />);
    expect(section('Recovery time')).toHaveTextContent('2 not yet recovered; they are left out of the durations above.');
    expect(section('Recovery time')).toHaveTextContent('only the duration is shown');
  });

  it('says no failure has recovered when every failure is still open', () => {
    render(<DoraPanel report={report({ recoveryTime: dur(0, null, null), unrecovered: 1 })} />);
    expect(section('Recovery time')).toHaveTextContent('No failed deployment has recovered yet.');
    expect(section('Recovery time')).toHaveTextContent('1 not yet recovered');
  });

  it('marks the week still running as partial, by more than colour', () => {
    const { container } = render(<DoraPanel report={report()} today="2026-11-12" />);
    const bars = container.querySelectorAll('rect.work-chart-bar');
    expect(bars).toHaveLength(2);
    expect(bars[0].getAttribute('data-muted')).toBeNull();
    expect(bars[1].getAttribute('data-muted')).toBe('true');
    expect(section('Deployment frequency')).toHaveTextContent('a muted, dashed bar is a partial week');
  });

  it('labels the merge mix as this window, apart from the all-time flagged lane', () => {
    render(<DoraPanel report={report()} />);
    const mix = section('How changes were merged');
    expect(mix).toHaveTextContent('Flagged merges in this window: 2');
    expect(mix).toHaveTextContent('of which not yet acknowledged: 1');
    expect(mix).toHaveTextContent('counts every merge still waiting to be acknowledged, whenever it happened');
  });

  it('says whether it covers one project or every project', () => {
    const { rerender } = render(<DoraPanel report={report()} />);
    expect(screen.getByText(/carry an item of this project/)).toBeInTheDocument();
    rerender(<DoraPanel report={report({ product: 'wikantik' })} wholeProduct />);
    expect(screen.getByText(/changes of every project/)).toBeInTheDocument();
  });

  it('shows no person and no login anywhere', () => {
    const { container } = render(<DoraPanel report={report()} />);
    expect(container.textContent).toMatch(/not how any person performs/);
  });
});
