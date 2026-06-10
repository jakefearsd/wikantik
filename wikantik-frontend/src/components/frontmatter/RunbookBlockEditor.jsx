// RunbookBlockEditor.jsx
// Nested editor for the `runbook:` block. Each sub-field is a list of strings, edited as a textarea
// with one entry per line. The server-side FrontmatterRunbookValidator remains the authority; this
// renders the per-sub-field violations it returns (field path `runbook.<key>`) inline.
import ViolationList from './ViolationList';

const RUNBOOK_FIELDS = [
  { k: 'when_to_use', label: 'When to use (≥1)' },
  { k: 'inputs', label: 'Inputs' },
  { k: 'steps', label: 'Steps (≥2)' },
  { k: 'pitfalls', label: 'Pitfalls (≥1, use "(none known)")' },
  { k: 'related_tools', label: 'Related tools' },
  { k: 'references', label: 'References' },
];

export default function RunbookBlockEditor({ value, onChange, violations = [] }) {
  const block = value && typeof value === 'object' && !Array.isArray(value) ? value : {};

  const setList = (k, text) => {
    const list = text.split('\n').map((s) => s.trim()).filter(Boolean);
    const next = { ...block };
    if (list.length === 0) delete next[k];
    else next[k] = list;
    onChange(Object.keys(next).length ? next : undefined);
  };

  const violationsFor = (k) => violations.filter((v) => v.field === `runbook.${k}`);

  return (
    <div className="fm-runbook">
      {RUNBOOK_FIELDS.map(({ k, label }) => (
        <div key={k} className="fm-runbook-field">
          <label className="fm-label">{label}</label>
          <textarea
            className="fm-textarea"
            aria-label={`runbook ${k}`}
            placeholder="one per line"
            value={(Array.isArray(block[k]) ? block[k] : []).join('\n')}
            onChange={(e) => setList(k, e.target.value)}
          />
          <ViolationList violations={violationsFor(k)} />
        </div>
      ))}
    </div>
  );
}
