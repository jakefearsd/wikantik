import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import FilterPanel from './FilterPanel.jsx';
import { INITIAL_FILTER_STATE, PRESETS, applyPreset } from './filter-state.js';

const snapshot = {
  nodes: [
    { id: 'a', cluster: 'math', tags: ['opt'], type: 'article', status: 'active' },
    { id: 'b', cluster: 'ops', tags: ['simplex'], type: 'hub', status: 'active' },
    { id: 'c', cluster: null, tags: [], type: 'article', status: 'draft' },
  ],
  edges: [],
};

describe('FilterPanel', () => {
  it('renders four preset buttons', () => {
    render(<FilterPanel state={INITIAL_FILTER_STATE} snapshot={snapshot} onChange={() => {}} />);
    expect(screen.getByRole('button', { name: /full/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /backbone/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /communities/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /tags/i })).toBeInTheDocument();
  });

  it('clicking Backbone calls onChange with backbone preset state', () => {
    const onChange = vi.fn();
    render(<FilterPanel state={INITIAL_FILTER_STATE} snapshot={snapshot} onChange={onChange} />);
    fireEvent.click(screen.getByRole('button', { name: /backbone/i }));
    expect(onChange).toHaveBeenCalled();
    const next = onChange.mock.calls[0][0];
    expect(next.preset).toBe(PRESETS.BACKBONE);
    expect(next.hubsOnly).toBe(true);
  });

  it('in Backbone preset, shows +1 hop toggle', () => {
    const state = applyPreset(INITIAL_FILTER_STATE, PRESETS.BACKBONE);
    render(<FilterPanel state={state} snapshot={snapshot} onChange={() => {}} />);
    expect(screen.getByLabelText(/\+1 hop neighbors/i)).toBeInTheDocument();
  });

  it('in Communities preset, lists each cluster with node count', () => {
    const state = applyPreset(INITIAL_FILTER_STATE, PRESETS.COMMUNITIES);
    render(<FilterPanel state={state} snapshot={snapshot} onChange={() => {}} />);
    expect(screen.getByText(/math/i)).toBeInTheDocument();
    expect(screen.getByText(/ops/i)).toBeInTheDocument();
    expect(screen.getByText(/unclustered/i)).toBeInTheDocument();
  });

  it('clicking a cluster in Communities preset toggles it in state', () => {
    const state = applyPreset(INITIAL_FILTER_STATE, PRESETS.COMMUNITIES);
    const onChange = vi.fn();
    render(<FilterPanel state={state} snapshot={snapshot} onChange={onChange} />);
    fireEvent.click(screen.getByRole('button', { name: /math/i }));
    const next = onChange.mock.calls[0][0];
    expect(next.clusters.has('math')).toBe(true);
  });

  it('in Tags preset, shows tag checkboxes derived from snapshot with counts', () => {
    const state = applyPreset(INITIAL_FILTER_STATE, PRESETS.TAGS);
    render(<FilterPanel state={state} snapshot={snapshot} onChange={() => {}} />);
    expect(screen.getByLabelText(/opt/)).toBeInTheDocument();
    expect(screen.getByLabelText(/simplex/)).toBeInTheDocument();
  });

  it('shows active chip for each selected cluster and removes on click', () => {
    let state = applyPreset(INITIAL_FILTER_STATE, PRESETS.COMMUNITIES);
    state = { ...state, clusters: new Set(['math']) };
    const onChange = vi.fn();
    render(<FilterPanel state={state} snapshot={snapshot} onChange={onChange} />);
    const chip = screen.getByRole('button', { name: /cluster: math ×/i });
    fireEvent.click(chip);
    const next = onChange.mock.calls[0][0];
    expect(next.clusters.has('math')).toBe(false);
  });
});
