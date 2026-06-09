// Tabs.jsx
// Simple ARIA tablist. `tabs` is [{id,label}]; `active` is the selected id; the caller renders
// the active panel as `children`.
export default function Tabs({ tabs = [], active, onChange, children }) {
  return (
    <div className="ui-tabs">
      <div className="ui-tabs-list" role="tablist">
        {tabs.map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={t.id === active}
            className={`ui-tab${t.id === active ? ' ui-tab-active' : ''}`}
            onClick={() => onChange?.(t.id)}
          >
            {t.label}
          </button>
        ))}
      </div>
      <div className="ui-tabs-panel" role="tabpanel">
        {children}
      </div>
    </div>
  );
}
