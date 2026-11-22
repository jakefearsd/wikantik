import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Select from '../ui/Select';
import Spinner from '../ui/Spinner';
import { listReportable, submitIntake, uploadAttachment } from '../../api/work';
import { useDocumentTitle } from '../../hooks/useDocumentTitle';
import { uploadErrorText } from './attachmentErrors';
import CharCount from './CharCount';
import WorkLoadError from './WorkLoadError';

const MAX_TITLE = 200;
const MAX_BODY = 20000;
const LEVELS = [{ value: 'story', label: 'Story' }, { value: 'task', label: 'Task' }];
const TYPES = [{ value: 'feature', label: 'Feature' }, { value: 'bug', label: 'Bug' }, { value: 'chore', label: 'Chore' }];
const NONE = 'You cannot file work into any project.';

/** One line per file chosen on the form: waiting, sending, sent, or why it did not go. */
function UploadList({ rows }) {
  return (
    <ul className="work-upload-list" aria-label="Files">
      {rows.map((r) => (
        <li key={r.id} className="work-upload-row" data-state={r.state}>
          <span>{r.file.name}</span>{' '}
          <span className={r.state === 'failed' ? 'work-form-error' : 'work-muted'}>
            {r.state === 'sent' ? 'Attached' : r.state === 'sending' ? 'Sending…' : r.state === 'failed' ? r.error : 'Waiting'}
          </span>
        </li>
      ))}
    </ul>
  );
}

/**
 * /work/submit: file a request into a project the viewer may file into (listReportable), starting as a draft that
 * the project's members triage. Files chosen on the form upload one after another once the item exists; a failed
 * upload leaves the item filed, says so with a link to it, and offers a retry. Nothing typed is lost on a failure.
 */
export default function WorkSubmit() {
  useDocumentTitle('File a request');
  const navigate = useNavigate();
  const [projects, setProjects] = useState({ rows: null, error: null });
  const [form, setForm] = useState({ projectKey: '', level: 'story', type: 'feature', title: '', body: '' });
  const [files, setFiles] = useState([]);
  const [fieldError, setFieldError] = useState(null); // { field, message }
  const [busy, setBusy] = useState(false);
  const [filed, setFiled] = useState(null); // { key } once the item exists
  const [rows, setRows] = useState([]);
  const inFlight = useRef(false);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);

  const load = useCallback(() => listReportable()
    .then((rowsIn) => {
      if (!alive.current) return;
      const list = rowsIn || [];
      setProjects({ rows: list, error: null });
      setForm((f) => (f.projectKey || list.length === 0 ? f : { ...f, projectKey: list[0].key }));
    })
    .catch((error) => {
      console.warn('WorkSubmit: could not load the projects to file into', error);
      if (alive.current) setProjects((p) => ({ rows: p.rows, error }));
    }), []);
  useEffect(() => { load(); }, [load]);

  const set = (name) => (value) => setForm((f) => ({ ...f, [name]: value }));

  function validate() {
    if (!form.projectKey) return { field: 'projectKey', message: 'Choose where to file this.' };
    if (!form.title.trim()) return { field: 'title', message: 'Enter a title.' };
    if (form.title.trim().length > MAX_TITLE) return { field: 'title', message: `A title may be at most ${MAX_TITLE} characters.` };
    if (form.body.length > MAX_BODY) return { field: 'body', message: `The details may be at most ${MAX_BODY.toLocaleString('en-US')} characters.` };
    return null;
  }

  /** Sends the files that have not gone yet, in order; resolves true when every one is attached. */
  async function upload(key, list) {
    const next = list.map((r) => ({ ...r }));
    const show = () => alive.current && setRows(next.map((r) => ({ ...r })));
    let allSent = true;
    for (const r of next) {
      if (r.state === 'sent') continue;
      r.state = 'sending'; show();
      try {
        await uploadAttachment(key, r.file);
        r.state = 'sent';
      } catch (e) {
        console.warn('WorkSubmit: could not attach', r.file.name, e);
        r.state = 'failed'; r.error = uploadErrorText(e, null, key); allSent = false;
      }
      show();
    }
    return allSent;
  }

  async function finish(key, list) {
    const ok = await upload(key, list);
    if (!alive.current) return;
    setBusy(false); inFlight.current = false;
    if (ok) navigate(`/work/item/${key}`);
  }

  function onSubmit(e) {
    e.preventDefault();
    if (inFlight.current || filed) return;
    const problem = validate();
    setFieldError(problem);
    if (problem) return;
    inFlight.current = true; setBusy(true);
    submitIntake({
      projectKey: form.projectKey, level: form.level, type: form.type, title: form.title.trim(), body: form.body,
    })
      .then((view) => {
        const key = view?.key;
        const list = files.map((file, id) => ({ id, file, state: 'waiting', error: null }));
        if (!alive.current) return undefined;
        setFiled({ key }); setRows(list);
        return finish(key, list);
      })
      .catch((err) => {
        console.warn('WorkSubmit: could not file the request', err);
        if (!alive.current) return;
        setBusy(false); inFlight.current = false;
        setFieldError({ field: err?.body?.field || null, message: err?.message || err?.body?.message || 'Could not file the request. Try again.' });
      });
  }

  function retry() {
    if (inFlight.current || !filed) return;
    inFlight.current = true; setBusy(true);
    finish(filed.key, rows);
  }

  if (projects.error && !projects.rows) {
    return <WorkLoadError error={projects.error} fallback="Could not load where you can file." onRetry={load} />;
  }
  if (!projects.rows) return <Spinner label="Loading…" />;
  if (projects.rows.length === 0 && !filed) {
    return (
      <div className="work-submit">
        <h1 className="work-heading">File a request</h1>
        <EmptyState message={NONE} />
        <p><Link to="/work">Back to Work</Link></p>
      </div>
    );
  }

  const overLimit = form.title.trim().length > MAX_TITLE || form.body.length > MAX_BODY;
  const failed = rows.filter((r) => r.state === 'failed');
  const errorFor = (field) => (fieldError && fieldError.field === field ? fieldError.message : null);
  const general = fieldError && !['projectKey', 'title', 'body'].includes(fieldError.field) ? fieldError.message : null;

  if (filed) {
    return (
      <div className="work-submit">
        <h1 className="work-heading">File a request</h1>
        <p role="status">Filed as <Link to={`/work/item/${filed.key}`}>{filed.key}</Link>.</p>
        {rows.length > 0 && <UploadList rows={rows} />}
        {failed.length > 0 && !busy && (
          <div className="work-form-error" role="alert">
            Your request was filed, but {failed.length === 1 ? 'a file' : `${failed.length} files`} did not upload.
            Open <Link to={`/work/item/${filed.key}`}>{filed.key}</Link> to see it, or try again.
            <div className="work-actions">
              <button type="button" className="btn btn-primary btn-sm" onClick={retry}>Retry the upload</button>
              <Link to={`/work/item/${filed.key}`} className="btn btn-ghost btn-sm">Go to {filed.key}</Link>
            </div>
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="work-submit">
      <h1 className="work-heading">File a request</h1>
      {projects.error && (
        <div className="error-banner" role="alert">
          Could not refresh where you can file: {projects.error.message}{' '}
          <button type="button" className="btn btn-secondary btn-sm" onClick={load}>Retry</button>
        </div>
      )}
      <form className="work-form" onSubmit={onSubmit} noValidate>
        <label className="work-form-field" htmlFor="work-submit-project">Project</label>
        <Select id="work-submit-project" ariaLabel="Project" value={form.projectKey} disabled={busy}
          options={projects.rows.map((p) => ({ value: p.key, label: p.name || p.key }))} onChange={set('projectKey')} />
        {errorFor('projectKey') && <div className="work-form-error" role="alert">{errorFor('projectKey')}</div>}
        <div className="work-form-row">
          <div>
            <label className="work-form-field" htmlFor="work-submit-level">Kind of work</label>
            <Select id="work-submit-level" ariaLabel="Level" value={form.level} options={LEVELS} disabled={busy} onChange={set('level')} />
          </div>
          <div>
            <label className="work-form-field" htmlFor="work-submit-type">Type</label>
            <Select id="work-submit-type" ariaLabel="Type" value={form.type} options={TYPES} disabled={busy} onChange={set('type')} />
          </div>
        </div>
        <label className="work-form-field" htmlFor="work-submit-title">Title</label>
        <input id="work-submit-title" className="form-input" value={form.title} disabled={busy}
          aria-invalid={errorFor('title') ? 'true' : undefined} onChange={(e) => set('title')(e.target.value)} />
        {errorFor('title') && <div className="work-form-error" role="alert">{errorFor('title')}</div>}
        <CharCount length={form.title.length} limit={MAX_TITLE} noun="title" />
        <label className="work-form-field" htmlFor="work-submit-body">Details</label>
        <textarea id="work-submit-body" className="form-input work-form-body" rows={6} value={form.body} disabled={busy}
          onChange={(e) => set('body')(e.target.value)} />
        <CharCount length={form.body.length} limit={MAX_BODY} noun="text" />
        <label className="work-form-field" htmlFor="work-submit-files">Files</label>
        <input id="work-submit-files" type="file" multiple disabled={busy}
          onChange={(e) => setFiles([...(e.target.files || [])])} />
        {general && <div className="work-form-error" role="alert">{general}</div>}
        <div className="work-actions">
          <button type="submit" className="btn btn-primary" disabled={busy || overLimit}>{busy ? 'Filing…' : 'File request'}</button>
          <Link to="/work" className="btn btn-ghost">Cancel</Link>
        </div>
      </form>
    </div>
  );
}
