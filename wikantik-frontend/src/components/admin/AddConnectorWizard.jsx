// AddConnectorWizard.jsx
//
// Guided Add Connector wizard (T22 of the connector-admin-ui plan) — the
// centerpiece UX for the connector-framework feature. One component, one
// `step` state machine: type -> source -> [authorize] -> test -> review.
// The authorize step only exists for types with a typeable secret (github
// token, confluence api_token, gdrive client_secret — never refresh_token,
// which is only ever obtained via the OAuth dance on the detail page).
//
// State lives entirely in this component (id, per-type config via
// ConnectorSettingsForm's onSubmit body, the typed secret value) so Back/
// Next never loses what the user entered — each step subtree unmounts on
// navigation, so what's persisted here is what survives.
//
// The typed secret is NEVER sent until Review's Save, and even then only via
// setCredential (a dedicated write) — never folded into the connector config.
import { useMemo, useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { api } from '../../api/client';
import PageHeader from './PageHeader';
import ConnectorSettingsForm from './ConnectorSettingsForm';
import { CONNECTOR_TYPES, TYPE_ORDER } from './connectorGuides';
import '../../styles/admin.css';

const ID_PATTERN = /^[a-z0-9-]{1,64}$/;
const ID_HELP = 'Lowercase letters, digits, and hyphens only (max 64 characters).';

function errorMessage(err, fallback) {
  return err?.body?.message || err?.message || fallback;
}

function formatConfigValue(field, value) {
  if (field.type === 'list') return Array.isArray(value) && value.length ? value.join(', ') : '—';
  if (field.type === 'bool') return value ? 'Yes' : 'No';
  if (value === undefined || value === null || value === '') return '—';
  return String(value);
}

export default function AddConnectorWizard() {
  const navigate = useNavigate();

  const [type, setType] = useState(null);
  const [step, setStep] = useState('type');

  const [id, setId] = useState('');
  const [idError, setIdError] = useState(null);
  const [sourceBody, setSourceBody] = useState(undefined);
  const [sourceErrors, setSourceErrors] = useState({});

  const [secretValue, setSecretValue] = useState('');

  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState(null);

  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState(null);

  const meta = type ? CONNECTOR_TYPES[type] : null;
  const hasAuthorize = !!meta?.authGuide;
  const secretName = meta?.authGuide?.secretName;

  const stepKeys = useMemo(
    () => ['type', 'source', ...(hasAuthorize ? ['authorize'] : []), 'test', 'review'],
    [hasAuthorize]
  );

  const goRelative = (delta) => {
    const idx = stepKeys.indexOf(step);
    const target = stepKeys[idx + delta];
    if (target) setStep(target);
  };
  const goBack = () => goRelative(-1);
  const goForward = () => goRelative(1);

  const selectType = (newType) => {
    if (newType !== type) {
      setSourceBody(undefined);
      setSourceErrors({});
      setSecretValue('');
      setTestResult(null);
      setSaveError(null);
    }
    setType(newType);
    setStep('source');
  };

  const handleSourceNext = (body) => {
    // Any pass through Source invalidates a previous test run — the result
    // may describe settings that no longer match what will be saved.
    setTestResult(null);

    const trimmedId = id.trim();
    const nextIdError = ID_PATTERN.test(trimmedId) ? null : ID_HELP;

    const fieldErrors = {};
    (meta.fields || []).forEach((field) => {
      if (!field.required) return;
      const value = body.config[field.name];
      const empty = field.type === 'list'
        ? !(Array.isArray(value) && value.length)
        : (value === undefined || value === null || value === '');
      if (empty) fieldErrors[field.name] = `${field.label} is required.`;
    });

    setIdError(nextIdError);
    setSourceErrors(fieldErrors);
    setSourceBody(body);

    if (nextIdError || Object.keys(fieldErrors).length) return;
    goForward();
  };

  const handleSecretChange = (value) => {
    setSecretValue(value);
    // A different credential invalidates any previous test result.
    setTestResult(null);
  };

  const handleRunTest = async () => {
    setTesting(true);
    setTestResult(null);
    try {
      const result = await api.connectors.test({
        type,
        config: sourceBody.config,
        credentials: secretValue && secretName ? { [secretName]: secretValue } : undefined,
      });
      setTestResult(result);
    } catch (err) {
      if (err.status === 422) {
        setSourceErrors(err.body?.errors || {});
        setStep('source');
      } else {
        setTestResult({ ok: false, found: 0, sample: [], complete: false, message: errorMessage(err, 'Test failed') });
      }
    } finally {
      setTesting(false);
    }
  };

  const doSave = async (sync) => {
    setSaving(true);
    setSaveError(null);
    const trimmedId = id.trim();
    try {
      const body = {
        id: trimmedId,
        type,
        enabled: sourceBody.enabled,
        syncIntervalHours: sourceBody.syncIntervalHours,
        config: sourceBody.config,
        cluster: sourceBody.cluster,
        defaultTags: sourceBody.defaultTags,
        pagePrefix: sourceBody.pagePrefix,
      };
      await api.connectors.create(body);

      if (secretValue && secretName) {
        await api.connectors.setCredential(trimmedId, secretName, secretValue);
      }
      if (sync) {
        // Fire-and-forget — the sync run is visible on the detail page's
        // Overview tab regardless of whether this call has landed yet.
        api.connectors.sync(trimmedId).catch((e) =>
          console.warn('post-create sync for', trimmedId, 'failed:', e?.message)
        );
      }
      navigate(`/admin/connectors/${encodeURIComponent(trimmedId)}${type === 'gdrive' ? '?next=authorize' : ''}`);
    } catch (err) {
      if (err.status === 422) {
        const errors = err.body?.errors || {};
        setIdError(errors.connector_id || null);
        setSourceErrors(errors);
        setStep('source');
      } else {
        setSaveError(errorMessage(err, 'Failed to create connector.'));
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="admin-users page-enter">
      <Link
        to="/admin/connectors"
        data-testid="back-to-connectors"
        style={{ color: 'var(--text-muted)', fontSize: '0.875rem', textDecoration: 'none', display: 'inline-block', marginBottom: 'var(--space-md)' }}
      >
        ← Connectors
      </Link>
      <PageHeader title="Add Connector" description="Set up a new external source to sync into the wiki." />

      {step === 'type' && <TypePicker onSelect={selectType} />}

      {step === 'source' && meta && (
        <SourceStep
          meta={meta}
          type={type}
          id={id}
          setId={setId}
          idError={idError}
          sourceErrors={sourceErrors}
          sourceBody={sourceBody}
          onValuesChange={setSourceBody}
          onNext={handleSourceNext}
          onBack={goBack}
        />
      )}

      {step === 'authorize' && meta && (
        <AuthorizeStep
          type={type}
          meta={meta}
          secretValue={secretValue}
          setSecretValue={handleSecretChange}
          onBack={goBack}
          onNext={goForward}
        />
      )}

      {step === 'test' && meta && (
        <TestStep
          type={type}
          testing={testing}
          testResult={testResult}
          onRunTest={handleRunTest}
          onBack={goBack}
          onNext={goForward}
        />
      )}

      {step === 'review' && meta && (
        <ReviewStep
          id={id}
          meta={meta}
          sourceBody={sourceBody}
          saving={saving}
          saveError={saveError}
          onBack={goBack}
          onSave={() => doSave(false)}
          onSaveAndSync={() => doSave(true)}
        />
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 0: type picker
// ---------------------------------------------------------------------------

function TypePicker({ onSelect }) {
  return (
    <div data-testid="wizard-step-type">
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))',
          gap: 'var(--space-md)',
        }}
      >
        {TYPE_ORDER.map((t) => {
          const meta = CONNECTOR_TYPES[t];
          return (
            <button
              type="button"
              key={t}
              data-testid={`type-card-${t}`}
              onClick={() => onSelect(t)}
              style={{
                textAlign: 'left',
                cursor: 'pointer',
                border: '1px solid var(--color-border, #ccc)',
                borderRadius: '6px',
                padding: 'var(--space-md)',
                background: 'var(--color-surface, #fff)',
              }}
            >
              <div style={{ fontSize: '1.5rem' }}>{meta.icon}</div>
              <h3 style={{ margin: '4px 0' }}>{meta.label}</h3>
              <p>{meta.blurb}</p>
              <p className="form-hint">{meta.goodFor}</p>
            </button>
          );
        })}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 1: source
// ---------------------------------------------------------------------------

function SourceStep({ meta, type, id, setId, idError, sourceErrors, sourceBody, onValuesChange, onNext, onBack }) {
  const helpFields = (meta.fields || []).filter((f) => f.help);

  return (
    <div data-testid="wizard-step-source">
      <h2>{meta.icon} {meta.label} — source details</h2>
      <div style={{ display: 'flex', gap: 'var(--space-lg)', flexWrap: 'wrap' }}>
        <div style={{ flex: '2 1 360px' }}>
          <div className="form-field">
            <label htmlFor="connector-id-input">Connector id</label>
            <input
              id="connector-id-input"
              type="text"
              data-testid="connector-id"
              value={id}
              onChange={(e) => setId(e.target.value)}
            />
            <p className="form-hint">{ID_HELP}</p>
            {idError && (
              <p className="field-error" role="alert" data-testid="connector-id-error" style={{ color: '#C44', fontSize: '0.8rem', marginTop: 4 }}>
                {idError}
              </p>
            )}
          </div>

          {/* onValuesChange mirrors in-progress edits up into wizard state so
              Back (which bypasses the form's submit) never loses them; the
              mirrored body then re-seeds initialValues on re-entry. */}
          <ConnectorSettingsForm
            type={type}
            initialValues={sourceBody}
            onValuesChange={onValuesChange}
            onSubmit={onNext}
            submitLabel="Next"
            errors={sourceErrors}
          />
        </div>

        {helpFields.length > 0 && (
          <aside style={{ flex: '1 1 240px' }} data-testid="source-instructions">
            <h3>Field guide</h3>
            <ul>
              {helpFields.map((f) => (
                <li key={f.name}><strong>{f.label}:</strong> {f.help}</li>
              ))}
            </ul>
          </aside>
        )}
      </div>

      <div className="modal-actions">
        <button type="button" className="btn btn-ghost" data-testid="wizard-back-button" onClick={onBack}>
          Back
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 2: authorize (only rendered when meta.authGuide is set)
// ---------------------------------------------------------------------------

function AuthorizeStep({ type, meta, secretValue, setSecretValue, onBack, onNext }) {
  const guide = meta.authGuide;
  const [copied, setCopied] = useState(false);
  const redirectUri = `${window.location.origin}/admin/connector-oauth/gdrive/callback`;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(redirectUri);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.warn('copy redirect URI to clipboard failed:', err?.message);
      setCopied(false);
    }
  };

  return (
    <div data-testid="wizard-step-authorize">
      <h2>Authorize</h2>
      <ol data-testid="authorize-steps">
        {guide.steps.map((s, i) => <li key={i}>{s}</li>)}
      </ol>
      {guide.optionalNote && <p className="form-hint">{guide.optionalNote}</p>}

      {type === 'gdrive' && (
        <div className="form-field" data-testid="gdrive-redirect-uri-block">
          <label>Redirect URI</label>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <code data-testid="redirect-uri-value" style={{ padding: '4px 8px', background: 'var(--color-surface-alt, #f5f5f5)', borderRadius: '4px' }}>
              {redirectUri}
            </code>
            <button type="button" className="btn btn-ghost" data-testid="copy-redirect-uri-button" onClick={copy}>
              {copied ? 'Copied ✓' : 'Copy'}
            </button>
          </div>
          <p className="form-hint">Register this exact URI in Google Cloud (step 5).</p>
        </div>
      )}

      <div className="form-field">
        <label htmlFor={`secret-input-${guide.secretName}`}>{guide.secretName}</label>
        <input
          id={`secret-input-${guide.secretName}`}
          type="password"
          data-testid={`secret-input-${guide.secretName}`}
          value={secretValue}
          onChange={(e) => setSecretValue(e.target.value)}
          autoComplete="off"
        />
        <p className="form-hint">Held only in this browser tab until you save — nothing is sent yet.</p>
      </div>

      <div className="modal-actions">
        <button type="button" className="btn btn-ghost" data-testid="wizard-back-button" onClick={onBack}>
          Back
        </button>
        <button type="button" className="btn btn-primary" data-testid="wizard-next-button" onClick={onNext}>
          Next
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 3: test
// ---------------------------------------------------------------------------

function TestStep({ type, testing, testResult, onRunTest, onBack, onNext }) {
  if (type === 'gdrive') {
    return (
      <div data-testid="wizard-step-test">
        <h2>Test</h2>
        <p data-testid="gdrive-test-deferred-message">
          Google Drive is tested after authorization — finish the wizard, then use Authorize with
          Google on the connector's Authorization tab.
        </p>
        <div className="modal-actions">
          <button type="button" className="btn btn-ghost" data-testid="wizard-back-button" onClick={onBack}>
            Back
          </button>
          <button type="button" className="btn btn-primary" data-testid="wizard-next-button" onClick={onNext}>
            Next
          </button>
        </div>
      </div>
    );
  }

  return (
    <div data-testid="wizard-step-test">
      <h2>Test</h2>
      <div className="modal-actions">
        <button type="button" className="btn btn-primary" data-testid="run-test-button" onClick={onRunTest} disabled={testing}>
          {testing ? 'Testing…' : 'Run test'}
        </button>
        {testResult?.ok ? (
          // The test passed — moving on is the expected path, not a skip.
          <button type="button" className="btn btn-primary" data-testid="test-continue" onClick={onNext}>
            Continue
          </button>
        ) : (
          <button type="button" className="btn btn-ghost" data-testid="skip-test-link" onClick={onNext}>
            Skip test
          </button>
        )}
      </div>
      {!testResult?.ok && (
        <p className="form-hint">
          Skipping means you won&rsquo;t confirm your settings and credentials work before saving.
        </p>
      )}

      {testResult && testResult.ok && (
        <div className="admin-row-message" role="status" data-testid="test-result-ok">
          found {testResult.found} item(s); first: {testResult.sample?.[0] ?? '—'}
        </div>
      )}
      {testResult && !testResult.ok && (
        <div className="error-banner" role="alert" data-testid="test-result-error">
          <p>{testResult.message}</p>
          <p>Check your settings in step 1.</p>
        </div>
      )}

      <div className="modal-actions">
        <button type="button" className="btn btn-ghost" data-testid="wizard-back-button" onClick={onBack}>
          Back
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 4: review & create
// ---------------------------------------------------------------------------

function ReviewStep({ id, meta, sourceBody, saving, saveError, onBack, onSave, onSaveAndSync }) {
  const cfg = sourceBody?.config || {};

  return (
    <div data-testid="wizard-step-review">
      <h2>Review &amp; create</h2>
      <div className="admin-table-wrapper">
        <table className="admin-table" data-testid="review-summary-table">
          <tbody>
            <tr><th>Id</th><td data-testid="review-id">{id}</td></tr>
            <tr><th>Type</th><td data-testid="review-type">{meta.icon} {meta.label}</td></tr>
            {meta.fields.map((f) => (
              <tr key={f.name}>
                <th>{f.label}</th>
                <td data-testid={`review-field-${f.name}`}>{formatConfigValue(f, cfg[f.name])}</td>
              </tr>
            ))}
            <tr><th>Enabled</th><td>{sourceBody?.enabled ? 'Yes' : 'No'}</td></tr>
            <tr><th>Sync interval</th><td>{sourceBody?.syncIntervalHours ? `${sourceBody.syncIntervalHours}h` : 'Manual sync only'}</td></tr>
            <tr><th>Cluster</th><td>{sourceBody?.cluster || '—'}</td></tr>
            <tr><th>Default tags</th><td>{sourceBody?.defaultTags || '—'}</td></tr>
            <tr><th>Page prefix</th><td>{sourceBody?.pagePrefix || '—'}</td></tr>
          </tbody>
        </table>
      </div>

      <h3>What happens next</h3>
      <p data-testid="review-expectations">{meta.expectations}</p>

      {saveError && <div className="error-banner" role="alert" data-testid="wizard-save-error">{saveError}</div>}

      <div className="modal-actions">
        <button type="button" className="btn btn-ghost" data-testid="wizard-back-button" onClick={onBack} disabled={saving}>
          Back
        </button>
        <button type="button" className="btn btn-primary" data-testid="wizard-save-button" onClick={onSave} disabled={saving}>
          {saving ? 'Saving…' : 'Save'}
        </button>
        <button type="button" className="btn btn-primary" data-testid="wizard-save-sync-button" onClick={onSaveAndSync} disabled={saving}>
          {saving ? 'Saving…' : 'Save & sync now'}
        </button>
      </div>
    </div>
  );
}
