import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import ClusterStatus from './ClusterStatus';

function show(props) {
  return render(
    <MemoryRouter>
      <ClusterStatus {...props} />
    </MemoryRouter>
  );
}

const DECLARED = {
  path: 'machine-learning',
  parent: null,
  hub_slug: 'MLHub',
  hub_declared: true,
  member_count: 44,
};

const UNDECLARED = {
  path: 'van-life',
  parent: null,
  hub_slug: null,
  hub_declared: false,
  member_count: 32,
};

describe('ClusterStatus', () => {
  // ---- anonymous / read-only: unchanged output, so SEO tuning is never confounded ----

  it('renders only the plain cluster chip for a reader who cannot edit', () => {
    show({ clusterStatus: UNDECLARED, isHub: false, canEdit: false });
    expect(screen.getByText('van-life')).toBeTruthy();
    expect(screen.queryByText(/not yet defined/i)).toBeNull();
  });

  it('renders nothing for a reader who cannot edit when the page has no cluster', () => {
    const { container } = show({ clusterStatus: null, isHub: false, canEdit: false });
    expect(container.firstChild).toBeNull();
  });

  it('never shows the unclustered badge to a reader who cannot edit', () => {
    show({ clusterStatus: null, isHub: false, canEdit: true });
    expect(screen.getByText(/unclustered/i)).toBeTruthy();

    const { container } = show({ clusterStatus: null, isHub: false, canEdit: false });
    expect(container.firstChild).toBeNull();
  });

  // ---- editor: the four states ----

  it('states the declaration explicitly on a hub page', () => {
    show({ clusterStatus: DECLARED, isHub: true, canEdit: true });
    const line = screen.getByTestId('cluster-declaration');
    expect(line.textContent).toMatch(/HUB/);
    expect(line.textContent).toMatch(/declares machine-learning/);
    expect(line.textContent).toMatch(/44 pages/);
  });

  it('names the parent on a sub-cluster hub', () => {
    show({
      clusterStatus: { ...DECLARED, path: 'machine-learning/mlops', parent: 'machine-learning', member_count: 6 },
      isHub: true,
      canEdit: true,
    });
    expect(screen.getByTestId('cluster-declaration').textContent).toMatch(/parent machine-learning/);
  });

  it('omits the parent clause for a top-level hub', () => {
    show({ clusterStatus: DECLARED, isHub: true, canEdit: true });
    expect(screen.getByTestId('cluster-declaration').textContent).not.toMatch(/parent/);
  });

  it('links a member page to the hub that declares its cluster', () => {
    show({ clusterStatus: DECLARED, isHub: false, canEdit: true });
    const link = screen.getByRole('link', { name: 'machine-learning' });
    expect(link.getAttribute('href')).toBe('/wiki/MLHub');
  });

  it('flags a member page whose cluster nothing declares', () => {
    show({ clusterStatus: UNDECLARED, isHub: false, canEdit: true });
    expect(screen.getByText(/not yet defined/i)).toBeTruthy();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('flags a page carrying no cluster at all', () => {
    show({ clusterStatus: null, isHub: false, canEdit: true });
    expect(screen.getByText(/unclustered/i)).toBeTruthy();
  });

  /**
   * hub_slug is omitted from the payload when null (Gson drops nulls), so the component
   * must key off the boolean rather than the absent field.
   */
  it('treats a payload with no hub_slug key as undeclared', () => {
    show({
      clusterStatus: { path: 'van-life', parent: null, hub_declared: false, member_count: 3 },
      isHub: false,
      canEdit: true,
    });
    expect(screen.getByText(/not yet defined/i)).toBeTruthy();
  });
});
