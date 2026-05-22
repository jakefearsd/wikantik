import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import ExistingHubDrilldown from './ExistingHubDrilldown';

const baseHub = (overrides = {}) => ({
  name: 'Hub',
  coherence: 0.81,
  hasBackingPage: true,
  members: [
    { name: 'M1', cosineToCentroid: 0.91 },
    { name: 'M2', cosineToCentroid: 0.82 },
    { name: 'M3', cosineToCentroid: 0.73 },
  ],
  ...overrides,
});

describe('ExistingHubDrilldown — base render', () => {
  it('returns null when no drilldown is supplied', () => {
    const { container } = render(
      <ExistingHubDrilldown drilldown={null} onRemoveMember={vi.fn()} removingMember={null} />
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders members with formatted cosine and coherence', () => {
    render(<ExistingHubDrilldown drilldown={baseHub()} onRemoveMember={vi.fn()} removingMember={null} />);
    expect(screen.getByText('Members')).toBeTruthy();
    expect(screen.getByText('M1')).toBeTruthy();
    expect(screen.getByText('0.91')).toBeTruthy();
    // coherence rendered to 2 dp
    expect(screen.getByText('0.81')).toBeTruthy();
  });

  it('shows the orphan callout when hub has no backing page', () => {
    render(
      <ExistingHubDrilldown
        drilldown={baseHub({ hasBackingPage: false })}
        onRemoveMember={vi.fn()}
        removingMember={null}
      />
    );
    expect(screen.getByText(/orphan — no backing page/)).toBeTruthy();
  });
});

describe('ExistingHubDrilldown — remove button behavior', () => {
  it('invokes onRemoveMember(hubName, memberName) when clicked', () => {
    const onRemoveMember = vi.fn();
    render(<ExistingHubDrilldown drilldown={baseHub()} onRemoveMember={onRemoveMember} removingMember={null} />);
    fireEvent.click(screen.getByTestId('existing-hub-member-remove-Hub-M1'));
    expect(onRemoveMember).toHaveBeenCalledWith('Hub', 'M1');
  });

  it('disables all remove buttons at the 2-member minimum', () => {
    render(
      <ExistingHubDrilldown
        drilldown={baseHub({ members: [{ name: 'A', cosineToCentroid: 0.6 }, { name: 'B', cosineToCentroid: 0.5 }] })}
        onRemoveMember={vi.fn()}
        removingMember={null}
      />
    );
    expect(screen.getByTestId('existing-hub-member-remove-Hub-A').disabled).toBe(true);
    expect(screen.getByTestId('existing-hub-member-remove-Hub-B').disabled).toBe(true);
  });

  it('disables only the member currently being removed', () => {
    render(<ExistingHubDrilldown drilldown={baseHub()} onRemoveMember={vi.fn()} removingMember="M2" />);
    expect(screen.getByTestId('existing-hub-member-remove-Hub-M2').disabled).toBe(true);
    expect(screen.getByTestId('existing-hub-member-remove-Hub-M1').disabled).toBe(false);
  });
});

describe('ExistingHubDrilldown — optional sections', () => {
  it('hides stub/near-miss/MLT/overlap sections when empty', () => {
    render(<ExistingHubDrilldown drilldown={baseHub()} onRemoveMember={vi.fn()} removingMember={null} />);
    expect(screen.queryByTestId('existing-hub-stubs-Hub')).toBeNull();
    expect(screen.queryByTestId('existing-hub-nearmiss-Hub')).toBeNull();
    expect(screen.queryByTestId('existing-hub-mlt-Hub')).toBeNull();
    expect(screen.queryByTestId('existing-hub-overlap-Hub')).toBeNull();
  });

  it('renders stub, near-miss, MLT, and overlap sections when populated', () => {
    const hub = baseHub({
      stubMembers: [{ name: 'StubX' }, { name: 'StubY' }],
      nearMissTfidf: [{ name: 'NearA', cosineToCentroid: 0.65 }],
      moreLikeThisLucene: [{ name: 'MltA', luceneScore: 1.234 }],
      overlapHubs: [{ name: 'OtherHub', centroidCosine: 0.4, sharedMemberCount: 2 }],
    });
    render(<ExistingHubDrilldown drilldown={hub} onRemoveMember={vi.fn()} removingMember={null} />);

    expect(screen.getByTestId('existing-hub-stubs-Hub')).toBeTruthy();
    expect(screen.getByText(/StubX, StubY/)).toBeTruthy();

    expect(screen.getByTestId('existing-hub-nearmiss-Hub')).toBeTruthy();
    expect(screen.getByText('NearA')).toBeTruthy();

    expect(screen.getByTestId('existing-hub-mlt-Hub')).toBeTruthy();
    expect(screen.getByText('MltA')).toBeTruthy();
    expect(screen.getByText(/score 1\.23/)).toBeTruthy();

    expect(screen.getByTestId('existing-hub-overlap-Hub')).toBeTruthy();
    expect(screen.getByText('OtherHub')).toBeTruthy();
    expect(screen.getByText(/2 shared member/)).toBeTruthy();
  });
});
