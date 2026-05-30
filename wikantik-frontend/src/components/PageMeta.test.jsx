import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import PageMeta from './PageMeta';

describe('PageMeta', () => {
  it('renders nothing when page is null', () => {
    const { container } = render(<PageMeta page={null} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders author and date when present', () => {
    render(<PageMeta page={{ author: 'Alice', lastModified: '2026-01-01T00:00:00Z' }} />);
    expect(screen.getByText('Alice')).toBeTruthy();
  });

  // ------- verification chip -------

  it('renders "Verified" badge (success) for authoritative confidence', () => {
    render(<PageMeta page={{ metadata: { confidence: 'authoritative' } }} />);
    const badge = screen.getByText('Verified');
    expect(badge.className).toMatch(/badge-success/);
  });

  it('renders "Provisional" badge (default) for provisional confidence', () => {
    render(<PageMeta page={{ metadata: { confidence: 'provisional' } }} />);
    const badge = screen.getByText('Provisional');
    expect(badge.className).toMatch(/badge-default/);
  });

  it('renders "Stale" badge (warning) for stale confidence', () => {
    render(<PageMeta page={{ metadata: { confidence: 'stale' } }} />);
    const badge = screen.getByText('Stale');
    expect(badge.className).toMatch(/badge-warning/);
  });

  it('includes title tooltip with relative timestamp when verified_at present', () => {
    render(<PageMeta page={{ metadata: { confidence: 'authoritative', verified_at: '2026-01-01T00:00:00Z' } }} />);
    const badge = screen.getByText('Verified');
    expect(badge.title).toMatch(/Verified /);
  });

  it('renders no verification badge when metadata has no confidence', () => {
    render(<PageMeta page={{ metadata: { cluster: 'MyCluster' } }} />);
    expect(screen.queryByText('Verified')).toBeNull();
    expect(screen.queryByText('Provisional')).toBeNull();
    expect(screen.queryByText('Stale')).toBeNull();
  });

  it('renders no verification badge when metadata is absent', () => {
    render(<PageMeta page={{ author: 'Bob' }} />);
    expect(screen.queryByText('Verified')).toBeNull();
    expect(screen.queryByText('Provisional')).toBeNull();
    expect(screen.queryByText('Stale')).toBeNull();
  });
});
