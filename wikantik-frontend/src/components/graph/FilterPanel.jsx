import { useMemo } from 'react';
import {
  PRESETS, applyPreset, toggleCluster, setTags, setTypes, setStatuses,
  setSearchText, setIncludeHubNeighbors, setShowUnclustered,
} from './filter-state.js';
import { colorForCluster } from './filter-engine.js';

function deriveClusters(snapshot) {
  const counts = new Map();
  let unclustered = 0;
  for (const n of snapshot.nodes) {
    if (n.cluster) counts.set(n.cluster, (counts.get(n.cluster) ?? 0) + 1);
    else unclustered += 1;
  }
  const entries = [...counts.entries()].sort((a, b) => b[1] - a[1]);
  return { entries, unclustered };
}

function deriveTags(snapshot) {
  const counts = new Map();
  for (const n of snapshot.nodes) {
    for (const t of (n.tags || [])) counts.set(t, (counts.get(t) ?? 0) + 1);
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1]);
}

function deriveTypes(snapshot) {
  const counts = new Map();
  for (const n of snapshot.nodes) if (n.type) counts.set(n.type, (counts.get(n.type) ?? 0) + 1);
  return [...counts.entries()].sort((a, b) => b[1] - a[1]);
}

function deriveStatuses(snapshot) {
  const counts = new Map();
  for (const n of snapshot.nodes) if (n.status) counts.set(n.status, (counts.get(n.status) ?? 0) + 1);
  return [...counts.entries()].sort((a, b) => b[1] - a[1]);
}

function ChipRow({ state, onChange }) {
  const chips = [];
  for (const c of state.clusters) {
    chips.push({ key: `cluster:${c}`, label: `Cluster: ${c}`, onRemove: () => onChange(toggleCluster(state, c)) });
  }
  for (const t of state.tags) {
    chips.push({ key: `tag:${t}`, label: `Tag: ${t}`, onRemove: () => {
      const next = new Set(state.tags); next.delete(t); onChange(setTags(state, [...next]));
    }});
  }
  for (const ty of state.types) {
    chips.push({ key: `type:${ty}`, label: `Type: ${ty}`, onRemove: () => {
      const next = new Set(state.types); next.delete(ty); onChange(setTypes(state, [...next]));
    }});
  }
  for (const st of state.statuses) {
    chips.push({ key: `status:${st}`, label: `Status: ${st}`, onRemove: () => {
      const next = new Set(state.statuses); next.delete(st); onChange(setStatuses(state, [...next]));
    }});
  }
  if (state.searchText) {
    chips.push({ key: 'search', label: `Search: ${state.searchText}`, onRemove: () => onChange(setSearchText(state, '')) });
  }
  if (chips.length === 0) return null;
  return (
    <div className="filter-chips">
      {chips.map(c => (
        <button key={c.key} type="button" className="filter-chip" onClick={c.onRemove}>
          {c.label} ×
        </button>
      ))}
    </div>
  );
}

export default function FilterPanel({ state, snapshot, onChange }) {
  const clusters = useMemo(() => deriveClusters(snapshot), [snapshot]);
  const tags = useMemo(() => deriveTags(snapshot), [snapshot]);
  const types = useMemo(() => deriveTypes(snapshot), [snapshot]);
  const statuses = useMemo(() => deriveStatuses(snapshot), [snapshot]);

  const presetButton = (preset, label) => (
    <button
      type="button"
      className={`filter-preset-pill ${state.preset === preset ? 'active' : ''}`}
      onClick={() => onChange(applyPreset(state, preset))}
    >
      {label}
    </button>
  );

  return (
    <div className="filter-panel">
      <div className="filter-preset-row">
        {presetButton(PRESETS.FULL, 'Full')}
        {presetButton(PRESETS.BACKBONE, 'Backbone')}
        {presetButton(PRESETS.COMMUNITIES, 'Communities')}
        {presetButton(PRESETS.TAGS, 'Tags')}
      </div>

      {state.preset === PRESETS.BACKBONE && (
        <div className="filter-section">
          <label>
            <input
              type="checkbox"
              checked={state.includeHubNeighbors}
              onChange={e => onChange(setIncludeHubNeighbors(state, e.target.checked))}
            />
            +1 hop neighbors
          </label>
          <div className="filter-caption">
            Hubs = author-marked (<code>type: hub</code>) OR top-5% degree
          </div>
        </div>
      )}

      {state.preset === PRESETS.COMMUNITIES && (
        <div className="filter-section">
          <div className="cluster-legend">
            {clusters.entries.map(([name, count]) => (
              <button
                key={name}
                type="button"
                className={`cluster-legend-item ${state.clusters.has(name) ? 'active' : ''}`}
                onClick={() => onChange(toggleCluster(state, name))}
              >
                <span className="cluster-swatch" style={{ background: colorForCluster(name) }} />
                <span className="cluster-name">{name}</span>
                <span className="cluster-count">{count}</span>
              </button>
            ))}
            <label className="cluster-unclustered">
              <input
                type="checkbox"
                checked={state.showUnclustered}
                onChange={e => onChange(setShowUnclustered(state, e.target.checked))}
              />
              Show unclustered ({clusters.unclustered})
            </label>
          </div>
        </div>
      )}

      {state.preset === PRESETS.TAGS && (
        <div className="filter-section">
          <div className="tag-picker">
            {tags.map(([tag, count]) => (
              <label key={tag} className="tag-option">
                <input
                  type="checkbox"
                  checked={state.tags.has(tag)}
                  onChange={e => {
                    const next = new Set(state.tags);
                    if (e.target.checked) next.add(tag); else next.delete(tag);
                    onChange(setTags(state, [...next]));
                  }}
                />
                {tag} ({count})
              </label>
            ))}
          </div>
          <div className="facet-row">
            <select
              value=""
              onChange={e => {
                if (!e.target.value) return;
                const next = new Set(state.types); next.add(e.target.value);
                onChange(setTypes(state, [...next]));
              }}
            >
              <option value="">+ type…</option>
              {types.map(([t, c]) => (<option key={t} value={t}>{t} ({c})</option>))}
            </select>
            <select
              value=""
              onChange={e => {
                if (!e.target.value) return;
                const next = new Set(state.statuses); next.add(e.target.value);
                onChange(setStatuses(state, [...next]));
              }}
            >
              <option value="">+ status…</option>
              {statuses.map(([s, c]) => (<option key={s} value={s}>{s} ({c})</option>))}
            </select>
            <input
              type="text"
              placeholder="Search names…"
              value={state.searchText}
              onChange={e => onChange(setSearchText(state, e.target.value))}
            />
          </div>
        </div>
      )}

      <ChipRow state={state} onChange={onChange} />
    </div>
  );
}
