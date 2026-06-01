import { hasActiveFacets } from '../utils/searchFacets';

// Date windows offered by the "Modified" facet. Cutoffs are computed from the
// current time at click; the chosen key is tracked separately so the active
// preset can be highlighted without fragile timestamp equality.
const DATE_PRESETS = [
  { key: 'week', label: 'Past week', days: 7 },
  { key: 'month', label: 'Past month', days: 30 },
  { key: 'year', label: 'Past year', days: 365 },
];

const DAY_MS = 24 * 60 * 60 * 1000;

function FacetGroup({ title, group, values, selection, onToggle }) {
  if (!values || values.length === 0) return null;
  const active = selection[group] || [];
  return (
    <div className="facet-group">
      <div className="facet-group-title">{title}</div>
      <div className="facet-chips">
        {values.map(({ value, count }) => {
          const isActive = active.includes(value);
          return (
            <button
              key={value}
              type="button"
              className={`facet-chip ${isActive ? 'active' : ''}`}
              aria-pressed={isActive}
              onClick={() => onToggle(group, value)}
            >
              {value} <span className="facet-chip-count">{count}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

export default function SearchFacets({ facets, selection, sinceKey, onToggle, onSetSince, onClear }) {
  const active = hasActiveFacets(selection);

  return (
    <aside className="search-facets" aria-label="Filter results">
      <div className="facet-group-header">
        <span className="facet-heading">Filters</span>
        {active && (
          <button type="button" className="facet-clear" onClick={onClear}>
            Clear filters
          </button>
        )}
      </div>

      <FacetGroup title="Topic" group="clusters" values={facets.clusters} selection={selection} onToggle={onToggle} />
      <FacetGroup title="Author" group="authors" values={facets.authors} selection={selection} onToggle={onToggle} />
      <FacetGroup title="Tag" group="tags" values={facets.tags} selection={selection} onToggle={onToggle} />

      <div className="facet-group">
        <div className="facet-group-title">Modified</div>
        <div className="facet-chips">
          <button
            type="button"
            className={`facet-chip ${sinceKey == null ? 'active' : ''}`}
            aria-pressed={sinceKey == null}
            onClick={() => onSetSince(null, null)}
          >
            Any time
          </button>
          {DATE_PRESETS.map(({ key, label, days }) => (
            <button
              key={key}
              type="button"
              className={`facet-chip ${sinceKey === key ? 'active' : ''}`}
              aria-pressed={sinceKey === key}
              onClick={() => onSetSince(Date.now() - days * DAY_MS, key)}
            >
              {label}
            </button>
          ))}
        </div>
      </div>
    </aside>
  );
}
