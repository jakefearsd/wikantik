// FieldWidget.jsx
// Renders one frontmatter field from its FieldSpec, dispatching on `widget` to the right ui/ primitive,
// and shows inline violations (with an "apply suggestion" affordance). Curated-open enums render as a
// free-entry Combobox; closed enums as a Select. Unknown keys are handled by FrontmatterEditor's
// Advanced area, not here.
import Select from '../ui/Select';
import Combobox from '../ui/Combobox';
import TagInput from '../ui/TagInput';
import Chip from '../ui/Chip';
import RunbookBlockEditor from './RunbookBlockEditor';
import ViolationList from './ViolationList';

function fmtScalar(value) {
  if (value == null) return '—';
  if (Array.isArray(value)) return value.join(', ');
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function TextField({ spec, value, onChange }) {
  const v = value ?? '';
  const showCounter = spec.minLen != null || spec.maxLen != null;
  const len = String(v).length;
  const warn =
    (spec.maxLen != null && len > spec.maxLen) ||
    (spec.minLen != null && len > 0 && len < spec.minLen);
  return (
    <div className="fm-text">
      <input
        className="ui-select fm-input"
        type="text"
        aria-label={spec.label}
        value={v}
        onChange={(e) => onChange(e.target.value)}
      />
      {showCounter && (
        <span className={`fm-counter${warn ? ' fm-counter-warn' : ''}`}>
          {len}
          {spec.maxLen != null ? `/${spec.maxLen}` : ''}
        </span>
      )}
    </div>
  );
}

function PageRefs({ value, onChange, pageSearch }) {
  const list = Array.isArray(value) ? value : [];
  const add = (name) => {
    const t = (name || '').trim();
    if (t && !list.includes(t)) onChange([...list, t]);
  };
  const remove = (name) => onChange(list.filter((x) => x !== name));
  return (
    <div className="fm-pagerefs">
      <div className="fm-pagerefs-chips">
        {list.map((n) => (
          <Chip key={n} label={n} onRemove={() => remove(n)} />
        ))}
      </div>
      <Combobox
        value=""
        onChange={add}
        fetchOptions={pageSearch}
        options={pageSearch ? undefined : []}
        placeholder="add related page…"
        allowFreeEntry
      />
    </div>
  );
}

export default function FieldWidget({ spec, value, onChange, violations, onApplySuggestion, pageSearch }) {
  const { key, label, widget, canonicalValues, open } = spec;

  let control;
  switch (widget) {
    case 'READONLY':
      control = (
        <span className="fm-readonly" data-testid={`fm-${key}`}>
          {fmtScalar(value)}
        </span>
      );
      break;
    case 'ENUM':
      control = open ? (
        <Combobox value={value ?? ''} options={canonicalValues} onChange={onChange} ariaLabel={label} placeholder={label} allowFreeEntry />
      ) : (
        <Select value={value ?? ''} options={canonicalValues} onChange={onChange} ariaLabel={label} placeholder="—" />
      );
      break;
    case 'TAGS':
      control = <TagInput value={Array.isArray(value) ? value : []} onChange={onChange} placeholder={label} id={key} />;
      break;
    case 'PAGE_REFS':
      control = <PageRefs value={value} onChange={onChange} pageSearch={pageSearch} />;
      break;
    case 'DATE':
      control = (
        <input className="ui-select fm-input" type="date" aria-label={label} value={value ?? ''}
          onChange={(e) => onChange(e.target.value)} />
      );
      break;
    case 'DATETIME':
      control = (
        <input className="ui-select fm-input" type="text" aria-label={label} value={value ?? ''}
          placeholder="YYYY-MM-DDThh:mm:ssZ" onChange={(e) => onChange(e.target.value)} />
      );
      break;
    case 'TRISTATE':
      control = (
        <Select
          value={value === true ? 'true' : value === false ? 'false' : ''}
          ariaLabel={label}
          options={[{ value: '', label: '(inherit)' }, { value: 'true', label: 'true' }, { value: 'false', label: 'false' }]}
          onChange={(v) => onChange(v === '' ? undefined : v === 'true')}
        />
      );
      break;
    case 'RUNBOOK_BLOCK':
      control = <RunbookBlockEditor value={value} onChange={onChange} />;
      break;
    case 'TEXTAREA':
      control = (
        <textarea className="fm-textarea" aria-label={label} value={value ?? ''}
          onChange={(e) => onChange(e.target.value)} />
      );
      break;
    case 'TEXT':
    default:
      control = <TextField spec={spec} value={value} onChange={onChange} />;
      break;
  }

  return (
    <div className={`fm-field fm-field-${key}`}>
      <label className="fm-label">{label}</label>
      {control}
      <ViolationList violations={violations} onApplySuggestion={onApplySuggestion} />
    </div>
  );
}
