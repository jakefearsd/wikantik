import { useCallback, useEffect, useRef, useState } from 'react';
import Spinner from '../ui/Spinner';
import { addWebhook, getMe, listWebhooks, removeWebhook, updateWebhook } from '../../api/work';
import { useToast } from '../../hooks/useToast';
import WorkConfirm from './WorkConfirm';

/** What a webhook may carry: the same list as the server's WebhookPayloads.SENDABLE. None ticked means all of them. */
export const WEBHOOK_EVENT_TYPES = [
  'created', 'edited', 'status', 'assigned', 'handoff', 'field', 'commented', 'linked', 'unlinked', 'claimed', 'released',
  'claim_expired', 'acknowledged', 'change_opened', 'change_updated', 'review_requested', 'change_reviewed', 'change_merged',
  'change_abandoned', 'deployment_requested', 'shipped', 'rolled_back',
];
const FORMATS = [{ value: 'generic', label: 'Signed JSON' }, { value: 'slack', label: 'Slack' }];
const FORMAT_LABEL = { generic: 'Signed JSON', slack: 'Slack' };
const PAUSED_TEXT = 'Its owner is no longer a lead; save it to take it over.';
const words = (s) => String(s).replace(/_/g, ' ');
const at = (iso) => (iso ? new Date(iso).toLocaleString() : '');

function EventTypeChecks({ idPrefix, value, onChange, disabled }) {
  const toggle = (t) => onChange(value.includes(t) ? value.filter((x) => x !== t) : [...value, t]);
  return (
    <fieldset className="work-webhook-types" disabled={disabled}>
      <legend className="work-muted">Events (none ticked sends every item event)</legend>
      {WEBHOOK_EVENT_TYPES.map((t) => (
        <label key={t} className="work-webhook-type" htmlFor={`${idPrefix}-${t}`}>
          <input id={`${idPrefix}-${t}`} type="checkbox" checked={value.includes(t)} onChange={() => toggle(t)} /> {words(t)}
        </label>
      ))}
    </fieldset>
  );
}

/**
 * Shows a secret once. `label` names the dialog; `children` is extra content (e.g. an address) above the secret.
 * The holder drops the secret on Done; it is never logged.
 */
export function SecretPanel({ secret, onDone, label = 'Webhook secret', children = null }) {
  const [copied, setCopied] = useState(null);
  const panel = useRef(null);
  useEffect(() => { panel.current?.focus(); }, []);
  function copy() {
    let pending;
    try {
      pending = navigator.clipboard?.writeText ? navigator.clipboard.writeText(secret) : null;
    } catch (err) {
      console.warn('WorkWebhooks: clipboard write threw', err?.name);
      pending = null;
    }
    if (!pending || typeof pending.then !== 'function') { setCopied(false); return; }
    pending.then(() => setCopied(true)).catch((err) => {
      console.warn('WorkWebhooks: clipboard write failed', err?.name);
      setCopied(false);
    });
  }
  return (
    <section ref={panel} tabIndex={-1} className="work-token-panel" role="dialog" aria-modal="false" aria-label={label}>
      <p role="status" aria-live="polite">
        Copy this secret now. It cannot be shown again.
        {copied === true && ' Copied.'}
        {copied === false && ' Copying failed — select the secret and copy it by hand.'}
      </p>
      {children}
      <code className="work-token-value">{secret}</code>
      <div className="work-actions">
        <button type="button" className="btn btn-sm" onClick={copy}>Copy</button>
        <button type="button" className="btn btn-sm btn-primary" onClick={onDone}>Done</button>
      </div>
    </section>
  );
}

function AddForm({ projectKey, onAdded }) {
  const [f, setF] = useState({ url: '', format: 'generic', eventTypes: [] });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  function submit(e) {
    e.preventDefault();
    if (!/^https:\/\/\S+$/i.test(f.url.trim())) { setError('Give an https address.'); return; }
    setError(null); setBusy(true);
    addWebhook(projectKey, { url: f.url.trim(), format: f.format, eventTypes: f.eventTypes })
      .then((reply) => { setF({ url: '', format: 'generic', eventTypes: [] }); onAdded(reply?.secret || null); })
      .catch((err) => {
        console.warn('WorkWebhooks: could not add the webhook', err?.status);
        setError(err?.message || 'Could not add the webhook.');
      })
      .finally(() => setBusy(false));
  }

  return (
    <form className="work-form" onSubmit={submit} noValidate aria-label="Add a webhook">
      <label className="work-form-field" htmlFor="work-hook-url">Address (https)</label>
      <input id="work-hook-url" className="work-field-input" value={f.url} autoComplete="off"
        onChange={(e) => setF({ ...f, url: e.target.value })} aria-invalid={error ? 'true' : undefined} />
      <label className="work-form-field" htmlFor="work-hook-format">Format</label>
      <select id="work-hook-format" className="work-field-input" value={f.format} onChange={(e) => setF({ ...f, format: e.target.value })}>
        {FORMATS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
      <EventTypeChecks idPrefix="work-hook-new" value={f.eventTypes} onChange={(eventTypes) => setF({ ...f, eventTypes })} disabled={busy} />
      {error && <div className="work-form-error" role="alert">{error}</div>}
      <div className="work-actions">
        <button type="submit" className="btn btn-sm" disabled={busy}>Add webhook</button>
      </div>
    </form>
  );
}

function Row({ hook, projectKey, onChanged, onError }) {
  const [editing, setEditing] = useState(null); // null | string[] (event types being edited)
  const [removing, setRemoving] = useState(false);
  const [busy, setBusy] = useState(false);

  function run(promise, after) {
    setBusy(true);
    return promise
      .then(() => { after?.(); return onChanged(); })
      .catch((err) => {
        console.warn('WorkWebhooks: a change was refused', err?.status);
        onError(err?.message || 'Could not change the webhook.');
        setRemoving(false);
      })
      .finally(() => setBusy(false));
  }
  const off = !!hook.disabledAt;

  return (
    <li className="work-webhook" data-testid={`webhook-${hook.id}`}>
      <div className="work-webhook-head">
        <strong>{hook.target}</strong>
        <span className="work-chip">{FORMAT_LABEL[hook.format] || hook.format}</span>
        <span className="work-muted">Owner: {hook.ownerName || 'someone'}</span>
      </div>
      <div className="work-muted">
        {hook.lastDeliveredAt ? `Last delivery ${at(hook.lastDeliveredAt)}` : 'Nothing delivered yet'}
        {hook.eventTypes?.length ? ` · ${hook.eventTypes.map(words).join(', ')}` : ' · every item event'}
      </div>
      {hook.failingSince && (
        <div className="work-form-error">
          Failing since {at(hook.failingSince)}{hook.lastError ? `: ${hook.lastError}` : ''}
        </div>
      )}
      {off && <div className="work-form-error">Switched off {at(hook.disabledAt)}.</div>}
      {hook.paused && <div className="work-muted" role="status">{PAUSED_TEXT}</div>}
      {editing && (
        <>
          <EventTypeChecks idPrefix={`work-hook-${hook.id}`} value={editing} onChange={setEditing} disabled={busy} />
          <div className="work-actions">
            <button type="button" className="btn btn-sm btn-primary" disabled={busy}
              onClick={() => run(updateWebhook(projectKey, hook.id, { eventTypes: editing }), () => setEditing(null))}>Save</button>
            <button type="button" className="btn btn-sm btn-ghost" onClick={() => setEditing(null)}>Cancel</button>
          </div>
        </>
      )}
      {removing ? (
        <WorkConfirm yes="Yes, remove it" disabled={busy} onYes={() => run(removeWebhook(projectKey, hook.id))} onNo={() => setRemoving(false)}>
          Remove the webhook to {hook.target}? Nothing more is sent to it.
        </WorkConfirm>
      ) : (
        <div className="work-actions">
          {hook.paused && (
            <button type="button" className="btn btn-sm" disabled={busy}
              onClick={() => run(updateWebhook(projectKey, hook.id, { eventTypes: hook.eventTypes || [] }))}>Take over</button>
          )}
          {!editing && <button type="button" className="btn btn-sm" disabled={busy} onClick={() => setEditing(hook.eventTypes || [])}>Edit events</button>}
          <button type="button" className="btn btn-sm" disabled={busy}
            onClick={() => run(updateWebhook(projectKey, hook.id, { enabled: off }))}>{off ? 'Turn on' : 'Turn off'}</button>
          <button type="button" className="btn btn-sm btn-danger" disabled={busy} onClick={() => setRemoving(true)}>Remove</button>
        </div>
      )}
    </li>
  );
}

/**
 * A project's webhooks, for a lead or admin, and only where the wiki has webhooks on. The signing secret of a new one
 * is held only by the panel that shows it: Done drops it, and it is never logged.
 */
export default function WorkWebhooks({ projectKey, role, isAdmin = false, archived = false }) {
  const toast = useToast();
  const manages = role === 'lead' || isAdmin;
  const [available, setAvailable] = useState(null);
  const [state, setState] = useState({ hooks: null, error: null });
  const [secret, setSecret] = useState(null);
  const [actionError, setActionError] = useState(null);
  const seq = useRef(0);

  useEffect(() => {
    if (!manages) return undefined;
    let cancelled = false;
    getMe()
      .then((me) => { if (!cancelled) setAvailable(!!me?.notify?.webhooksAvailable); })
      .catch((e) => console.warn('WorkWebhooks: could not tell whether webhooks are on', e));
    return () => { cancelled = true; };
  }, [manages]);

  const load = useCallback(() => {
    const mine = ++seq.current;
    return listWebhooks(projectKey)
      .then((hooks) => { if (mine === seq.current) setState({ hooks: hooks || [], error: null }); })
      .catch((e) => {
        console.warn('WorkWebhooks: could not load the webhooks', e);
        if (mine === seq.current) setState((s) => ({ hooks: s.hooks, error: e?.message || 'Could not load the webhooks.' }));
      });
  }, [projectKey]);
  useEffect(() => { if (manages && available) load(); }, [manages, available, load]);
  // A secret belongs to the project it was made for.
  useEffect(() => () => setSecret(null), [projectKey]);

  if (!manages || !available) return null;
  const { hooks, error } = state;

  return (
    <section className="work-section" aria-label="Webhooks">
      <h2 className="work-subheading">Webhooks</h2>
      <p className="work-muted">
        A webhook sends each item event to an address you give. It acts for the lead who added it, and pauses while that
        person is not a lead.
      </p>
      {secret && <SecretPanel secret={secret} onDone={() => setSecret(null)} />}
      {error && (
        <div className="work-form-error" role="alert">
          {error} <button type="button" className="btn btn-sm" onClick={load}>Retry</button>
        </div>
      )}
      {actionError && <div className="work-form-error" role="alert">{actionError}</div>}
      {hooks == null && !error && <Spinner label="Loading…" />}
      {hooks != null && hooks.length === 0 && <p className="work-muted">No webhooks.</p>}
      {hooks != null && hooks.length > 0 && (
        <ul className="work-webhook-list">
          {hooks.map((h) => (
            <Row key={h.id} hook={h} projectKey={projectKey}
              onChanged={() => { setActionError(null); return load(); }} onError={setActionError} />
          ))}
        </ul>
      )}
      {!archived && (
        <AddForm projectKey={projectKey}
          onAdded={(s) => { setActionError(null); setSecret(s); toast.success('Webhook added'); load(); }} />
      )}
    </section>
  );
}
