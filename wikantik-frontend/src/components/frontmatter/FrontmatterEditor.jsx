// FrontmatterEditor.jsx
// The structured frontmatter surface that shares the editor pane with the (body-only) CodeMirror.
// Renders one FieldWidget per schema FieldSpec, an Advanced area for unknown keys (preserved
// verbatim), and a Form ⇄ Raw YAML break-glass toggle. The parsed metadata OBJECT is canonical; this
// component never enforces — it renders the server's violations inline and lets the user always save.
//
// Props:
//   metadata      the current frontmatter object (canonical)
//   onChange      (nextMetadata) => void
//   violations    server-returned [{field,severity,code,message,suggestion}] from the last save/dry-run
//   schema        optional injected schema (tests); otherwise fetched via schemaClient
//   validateRaw   optional ({frontmatter}) => Promise<{metadata,violations}> for Raw→Form sync
//   pageSearch    optional (query) => Promise<options> for the related-pages picker
import { useEffect, useRef, useState } from 'react';
import Tabs from '../ui/Tabs';
import FieldWidget from './FieldWidget';
import { getSchema } from './schemaClient';
import { metadataToYaml } from '../../utils/frontmatterUtils';
import { api } from '../../api/client';

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

  if (!schema) return <div className="fm-editor-loading">Loading editor…</div>;

  const fields = schema.fields || [];
  const knownKeys = new Set(fields.map((f) => f.key));
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

  return (
    <div className="fm-editor">
      <Tabs
        tabs={[{ id: 'form', label: 'Form' }, { id: 'raw', label: 'Raw YAML' }]}
        active={tab}
        onChange={setTab}
      >
        {tab === 'form' ? (
          <div className="fm-form">
            {fields.map((f) => (
              <FieldWidget
                key={f.key}
                spec={f}
                value={metadata?.[f.key]}
                onChange={(v) => setField(f.key, v)}
                violations={violationsFor(f.key)}
                onApplySuggestion={(s) => setField(f.key, s)}
                pageSearch={pageSearch}
              />
            ))}
            <AdvancedKeyValues metadata={metadata || {}} unknownKeys={unknownKeys} onChange={onChange} />
          </div>
        ) : (
          <RawYaml metadata={metadata} onChange={onChange} validateRaw={validateRaw} />
        )}
      </Tabs>
    </div>
  );
}
