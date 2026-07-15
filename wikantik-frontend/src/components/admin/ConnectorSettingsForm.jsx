// ConnectorSettingsForm.jsx
//
// Renders the per-type config fields (from CONNECTOR_TYPES[type].fields) plus
// the common fields shared by every connector (enabled/syncIntervalHours/
// cluster/defaultTags/pagePrefix). Shared between ConnectorDetailPage's
// Settings tab and the connector-creation wizard (Task 22).
//
// PUT is full-replace: onSubmit is always called with the COMPLETE body —
// { config: {...all fields...}, enabled, syncIntervalHours, cluster,
// defaultTags, pagePrefix } — never a partial patch.
//
// Optional `onValuesChange(body)` prop: called with that same complete body
// on every value change (and once on mount). The Add Connector wizard uses
// it to mirror in-progress edits into parent state so Back/Next never loses
// them; the detail page's Settings tab simply omits it.
import { useEffect, useState } from 'react';
import { CONNECTOR_TYPES } from './connectorGuides';
import '../../styles/admin.css';

function initFieldValue(field, config) {
  const raw = config ? config[field.name] : undefined;
  if (raw !== undefined && raw !== null) return raw;
  if (field.default !== undefined) return field.default;
  if (field.type === 'bool') return false;
  if (field.type === 'list') return [];
  return '';
}

function serializeFieldValue(field, value) {
  if (field.type === 'list') {
    const lines = Array.isArray(value) ? value : String(value || '').split('\n');
    return lines.map((s) => s.trim()).filter(Boolean);
  }
  if (field.type === 'number') {
    return value === '' || value === null || value === undefined ? null : Number(value);
  }
  if (field.type === 'bool') {
    return !!value;
  }
  return value ?? '';
}

function FieldError({ name, errors }) {
  const message = errors?.[name];
  if (!message) return null;
  return (
    <p
      className="field-error"
      data-testid={`field-error-${name}`}
      role="alert"
      style={{ color: '#C44', fontSize: '0.8rem', marginTop: 4 }}
    >
      {message}
    </p>
  );
}

function TypeField({ field, value, onChange, disabled, errors }) {
  const testId = `field-${field.name}`;
  return (
    <div className="form-field">
      <label htmlFor={testId}>
        {field.label}
        {field.required && <span style={{ color: '#C44', marginLeft: 4 }}>*</span>}
      </label>
      {field.type === 'list' && (
        <textarea
          id={testId}
          data-testid={testId}
          rows={4}
          value={Array.isArray(value) ? value.join('\n') : value || ''}
          onChange={(e) => onChange(e.target.value.split('\n'))}
          disabled={disabled}
        />
      )}
      {field.type === 'bool' && (
        <input
          id={testId}
          type="checkbox"
          data-testid={testId}
          checked={!!value}
          onChange={(e) => onChange(e.target.checked)}
          disabled={disabled}
        />
      )}
      {field.type === 'number' && (
        <input
          id={testId}
          type="number"
          data-testid={testId}
          value={value ?? ''}
          onChange={(e) => onChange(e.target.value)}
          disabled={disabled}
        />
      )}
      {(field.type === 'text' || !field.type) && (
        <input
          id={testId}
          type="text"
          data-testid={testId}
          value={value ?? ''}
          onChange={(e) => onChange(e.target.value)}
          disabled={disabled}
        />
      )}
      {field.help && <p className="form-hint">{field.help}</p>}
      <FieldError name={field.name} errors={errors} />
    </div>
  );
}

export default function ConnectorSettingsForm({
  type,
  initialValues,
  onSubmit,
  onValuesChange,
  submitLabel = 'Save Settings',
  errors = {},
  readOnly = false,
}) {
  const meta = CONNECTOR_TYPES[type];
  const fields = meta?.fields || [];
  const initialConfig = initialValues?.config || {};

  const [values, setValues] = useState(() => {
    const initial = {};
    fields.forEach((field) => {
      initial[field.name] = initFieldValue(field, initialConfig);
    });
    return initial;
  });
  const [enabled, setEnabled] = useState(initialValues?.enabled ?? true);
  const [syncIntervalHours, setSyncIntervalHours] = useState(initialValues?.syncIntervalHours ?? 0);
  const [cluster, setCluster] = useState(initialValues?.cluster ?? '');
  const [defaultTags, setDefaultTags] = useState(initialValues?.defaultTags ?? '');
  const [pagePrefix, setPagePrefix] = useState(initialValues?.pagePrefix ?? '');
  const [submitting, setSubmitting] = useState(false);

  const setFieldValue = (name, val) => setValues((prev) => ({ ...prev, [name]: val }));

  const buildBody = () => ({
    // PUT is full-replace: seed from the incoming config so backend fields
    // the UI doesn't model (e.g. webcrawler user_agent) survive the save;
    // rendered-field values win over the seeded ones.
    config: {
      ...initialConfig,
      ...fields.reduce((acc, field) => {
        acc[field.name] = serializeFieldValue(field, values[field.name]);
        return acc;
      }, {}),
    },
    enabled,
    syncIntervalHours: syncIntervalHours === '' ? 0 : Number(syncIntervalHours),
    cluster,
    defaultTags,
    pagePrefix,
  });

  // Mirror every edit (and the mount-time defaults) to the parent when asked.
  // One effect over all six state slices beats threading a callback through
  // each individual onChange handler.
  useEffect(() => {
    if (onValuesChange) onValuesChange(buildBody());
    // buildBody/onValuesChange identities change per render; the state slices
    // below are the real change signal.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [values, enabled, syncIntervalHours, cluster, defaultTags, pagePrefix]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (readOnly || submitting) return;
    setSubmitting(true);
    const body = buildBody();
    try {
      await onSubmit(body);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} data-testid="connector-settings-form">
      {fields.map((field) => (
        <TypeField
          key={field.name}
          field={field}
          value={values[field.name]}
          onChange={(val) => setFieldValue(field.name, val)}
          disabled={readOnly}
          errors={errors}
        />
      ))}

      <div className="form-field">
        <label htmlFor="field-enabled" style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', textTransform: 'none' }}>
          <input
            id="field-enabled"
            type="checkbox"
            data-testid="field-enabled"
            checked={enabled}
            onChange={(e) => setEnabled(e.target.checked)}
            disabled={readOnly}
            style={{ width: 'auto' }}
          />
          Enabled
        </label>
        <FieldError name="enabled" errors={errors} />
      </div>

      <div className="form-field">
        <label htmlFor="field-syncIntervalHours">Sync interval (hours)</label>
        <input
          id="field-syncIntervalHours"
          type="number"
          data-testid="field-syncIntervalHours"
          value={syncIntervalHours}
          onChange={(e) => setSyncIntervalHours(e.target.value)}
          disabled={readOnly}
        />
        <p className="form-hint">0 = manual sync only.</p>
        <FieldError name="syncIntervalHours" errors={errors} />
      </div>

      <div className="form-field">
        <label htmlFor="field-cluster">Cluster</label>
        <input
          id="field-cluster"
          type="text"
          data-testid="field-cluster"
          value={cluster}
          onChange={(e) => setCluster(e.target.value)}
          disabled={readOnly}
        />
        <FieldError name="cluster" errors={errors} />
      </div>

      <div className="form-field">
        <label htmlFor="field-defaultTags">Default tags</label>
        <input
          id="field-defaultTags"
          type="text"
          data-testid="field-defaultTags"
          value={defaultTags}
          onChange={(e) => setDefaultTags(e.target.value)}
          disabled={readOnly}
        />
        <FieldError name="defaultTags" errors={errors} />
      </div>

      <div className="form-field">
        <label htmlFor="field-pagePrefix">Page prefix</label>
        <input
          id="field-pagePrefix"
          type="text"
          data-testid="field-pagePrefix"
          value={pagePrefix}
          onChange={(e) => setPagePrefix(e.target.value)}
          disabled={readOnly}
        />
        <p className="form-hint">Glued directly onto page names — include your own separator, e.g. News-</p>
        <FieldError name="pagePrefix" errors={errors} />
      </div>

      {!readOnly && (
        <div className="modal-actions">
          <button type="submit" className="btn btn-primary" data-testid="settings-submit-button" disabled={submitting}>
            {submitting ? 'Saving…' : submitLabel}
          </button>
        </div>
      )}
    </form>
  );
}
