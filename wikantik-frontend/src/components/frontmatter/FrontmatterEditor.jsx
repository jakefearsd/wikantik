// FrontmatterEditor.jsx
// The structured frontmatter surface that shares the editor pane with the (body-only) CodeMirror.
// Fields are split into an always-open "Common" block, a collapsible "More fields" disclosure for
// the rarer/specialized fields (runbook, verification, etc.), and a muted meta strip for the
// derived READONLY fields. An Advanced area lists unknown keys (preserved verbatim), and a
// Form ⇄ Raw YAML break-glass toggle stays available. The parsed metadata OBJECT is canonical;
// this component never enforces — it renders the server's violations inline and lets the user save.
//
// Props:
//   metadata      the current frontmatter object (canonical)
//   onChange      (nextMetadata) => void
//   violations    server-returned [{field,severity,code,message,suggestion}] from the last save/dry-run
//   schema        optional injected schema (tests); otherwise fetched via schemaClient
//   validateRaw   optional ({frontmatter}) => Promise<{metadata,violations}> for Raw→Form sync
//   pageSearch    optional (query) => Promise<options> for the related-pages picker
import { useEffect, useMemo, useRef, useState } from 'react';
import Tabs from '../ui/Tabs';
import FieldWidget from './FieldWidget';
import { getSchema } from './schemaClient';
import { metadataToYaml } from '../../utils/frontmatterUtils';
import { api } from '../../api/client';

// Always-open "Common" block. Everything else editable falls into the "More fields" disclosure;
// READONLY fields are surfaced (read-only) in the compact meta strip instead of the grid.
const COMMON_KEYS = ['title', 'type', 'status', 'summary', 'tags', 'cluster'];

function hasValue(v) {
  return !(v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0));
}

function fmtMeta(v) {
  if (v == null) return '';
  if (Array.isArray(v)) return v.join(', ');
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

function AdvancedKeyValues({ metadata, unknownKeys, onChange }) {
  if (unknownKeys.length === 0) return null;
  const removeKey = (k) => {
    const next = { ...metadata };
    delete next[k];
    onChange(next);
  };
  return (
    <details className="fm-advanced">
      <summary>
        Advanced ({unknownKeys.length} other {unknownKeys.length === 1 ? 'key' : 'keys'})
      </summary>
      <ul className="fm-advanced-list">
        {unknownKeys.map((k) => (
          <li key={k}>
            <code>{k}</code>: <code>{JSON.stringify(metadata[k])}</code>
            <button type="button" aria-label={`Remove ${k}`} onClick={() => removeKey(k)}>
              ×
            </button>
          </li>
        ))}
      </ul>
      <p className="fm-advanced-note">Edit these via the Raw YAML tab.</p>
    </details>
  );
}

function RawYaml({ metadata, onChange, validateRaw }) {
  const [text, setText] = useState(() => metadataToYaml(metadata));
  const [error, setError] = useState(null);
  const dirty = useRef(false);

  const sync = () => {
    if (!dirty.current) return;
    dirty.current = false;
    Promise.resolve(validateRaw({ frontmatter: text }))
      .then((res) => {
        if (res && res.metadata) {
          setError(null);
          onChange(res.metadata);
        } else {
          const yaml = (res?.violations || []).find((v) => v.field === '__yaml__');
          setError(yaml ? yaml.message : 'Could not parse YAML.');
        }
      })
      .catch(() => setError('Could not parse YAML.'));
  };

  return (
    <div className="fm-raw">
      <textarea
        className="fm-raw-textarea"
        aria-label="Raw frontmatter YAML"
        value={text}
        onChange={(e) => {
          dirty.current = true;
          setText(e.target.value);
        }}
        onBlur={sync}
      />
      {error && <p className="fm-raw-error">{error}</p>}
    </div>
  );
}

export default function FrontmatterEditor({
  metadata,
  onChange,
  violations = [],
  schema: schemaProp,
  validateRaw = (payload) => api.validateFrontmatter(payload),
  pageSearch,
}) {
  const [schema, setSchema] = useState(schemaProp ?? null);
  const [tab, setTab] = useState('form');

  useEffect(() => {
    if (schemaProp) {
      setSchema(schemaProp);
      return undefined;
    }
    let alive = true;
    getSchema()
      .then((s) => {
        if (alive) setSchema(s);
      })
      .catch(() => {
        /* schema fetch failed; editor stays in loading state — surfaced by the caller's error path */
      });
    return () => {
      alive = false;
    };
  }, [schemaProp]);

  // Partition the schema's fields once per schema: Common (always open), More (collapsible),
  // and the READONLY derived fields (meta strip). Order is preserved from the schema.
  const { commonFields, moreFields, readonlyFields } = useMemo(() => {
    const fields = schema?.fields || [];
    const common = [];
    const more = [];
    const readonly = [];
    for (const f of fields) {
      if (f.widget === 'READONLY') readonly.push(f);
      else if (COMMON_KEYS.includes(f.key)) common.push(f);
      else more.push(f);
    }
    return { commonFields: common, moreFields: more, readonlyFields: readonly };
  }, [schema]);

  // Reveal the "More fields" disclosure when one of its fields carries an ERROR
  // violation: a blocked save (422) on a non-Common field would otherwise be
  // invisible — the inline error renders inside the collapsed <details>, so the
  // user sees no reason the save failed. Warnings stay hidden to keep the default
  // footprint small. The disclosure remains user-toggleable (onToggle below).
  const [moreOpen, setMoreOpen] = useState(false);
  const moreHasError = useMemo(
    () =>
      moreFields.some((f) =>
        (violations || []).some(
          (v) =>
            (v.field === f.key || (v.field && v.field.startsWith(f.key + '.'))) &&
            (v.severity || '').toLowerCase() === 'error',
        ),
      ),
    [moreFields, violations],
  );
  useEffect(() => {
    if (moreHasError) setMoreOpen(true);
  }, [moreHasError]);

  if (!schema) return <div className="fm-editor-loading">Loading editor…</div>;

  const knownKeys = new Set((schema.fields || []).map((f) => f.key));
  const unknownKeys = Object.keys(metadata || {}).filter((k) => !knownKeys.has(k));

  const setField = (key, val) => {
    const next = { ...(metadata || {}) };
    const empty =
      val === undefined || val === null || val === '' || (Array.isArray(val) && val.length === 0);
    if (empty) delete next[key];
    else next[key] = val;
    onChange?.(next);
  };

  const violationsFor = (key) =>
    violations.filter((v) => v.field === key || (v.field && v.field.startsWith(key + '.')));

  const renderField = (f) => (
    <FieldWidget
      key={f.key}
      spec={f}
      value={metadata?.[f.key]}
      onChange={(v) => setField(f.key, v)}
      violations={violationsFor(f.key)}
      onApplySuggestion={(s) => setField(f.key, s)}
      pageSearch={pageSearch}
    />
  );

  const metaItems = readonlyFields
    .filter((f) => hasValue(metadata?.[f.key]))
    .map((f) => ({ key: f.key, label: f.label, value: fmtMeta(metadata[f.key]) }));

  // How many "More" fields already carry a value — surfaced on the summary so populated data is
  // signalled without forcing the disclosure open (keeps the default footprint small).
  const moreSetCount = moreFields.filter((f) => hasValue(metadata?.[f.key])).length;

  return (
    <div className="fm-editor">
      <Tabs
        tabs={[{ id: 'form', label: 'Form' }, { id: 'raw', label: 'Raw YAML' }]}
        active={tab}
        onChange={setTab}
      >
        {tab === 'form' ? (
          <div className="fm-form-wrap">
            <div className="fm-form">{commonFields.map(renderField)}</div>

            {moreFields.length > 0 && (
              <details
                className="fm-more"
                open={moreOpen}
                onToggle={(e) => setMoreOpen(e.currentTarget.open)}
              >
                <summary className="fm-more-summary">
                  More fields{moreSetCount > 0 ? ` (${moreSetCount} set)` : ''}
                </summary>
                <div className="fm-form">{moreFields.map(renderField)}</div>
              </details>
            )}

            {metaItems.length > 0 && (
              <div className="fm-meta-strip">
                {metaItems.map((m) => (
                  <span key={m.key} className="fm-meta-item" title={`${m.label}: ${m.value}`}>
                    <span className="fm-meta-label">{m.label}</span>: {m.value}
                  </span>
                ))}
              </div>
            )}

            <AdvancedKeyValues metadata={metadata || {}} unknownKeys={unknownKeys} onChange={onChange} />
          </div>
        ) : (
          <RawYaml metadata={metadata} onChange={onChange} validateRaw={validateRaw} />
        )}
      </Tabs>
    </div>
  );
}
