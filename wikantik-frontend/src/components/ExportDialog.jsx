import { useState, useEffect, useRef, useCallback } from 'react';
import { api } from '../api/client';
import Modal from './ui/Modal';
import TagInput from './ui/TagInput';
import Select from './ui/Select';

const UNSET = Symbol('unset');
const TYPE_OPTIONS = ['hub', 'article', 'reference', 'runbook', 'design'];
const DEBOUNCE_MS = 300;

function initialSelection(initialCluster) {
  return {
    clusters: initialCluster ? [initialCluster] : [],
    subclusters: true,
    tags: [],
    type: '',
    status: '',
    hops: 0,
    unresolved: 'keep',
  };
}

function formatMB(bytes) {
  return (bytes / (1024 * 1024)).toFixed(1);
}

/**
 * "Export to Obsidian" dialog — cluster/tag/type-scoped vault download.
 * Every control change re-runs a debounced GET /api/export/preview so the
 * page/attachment/size estimate (and the over-cap / empty-selection guard on
 * the download link) always reflects the current selection.
 */
export default function ExportDialog({ isOpen, onClose, initialCluster = '' }) {
  const [sel, setSel] = useState(() => initialSelection(initialCluster));
  const [options, setOptions] = useState({ clusters: [], tags: [] });
  const [preview, setPreview] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [denied, setDenied] = useState(false);

  const debounceRef = useRef(null);
  const abortRef = useRef(null);

  // Stable identity (empty deps — only closes over refs + setState setters,
  // both of which React guarantees are stable) so it can sit in a dependency
  // array without retriggering the effect below on every render.
  const schedulePreview = useCallback((nextSel) => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    // Supersede any in-flight preview now, not when the debounce fires: its result
    // describes an older selection, so it must neither land nor clear `loading`.
    if (abortRef.current) abortRef.current.abort();
    abortRef.current = null;
    debounceRef.current = setTimeout(() => {
      const controller = new AbortController();
      abortRef.current = controller;
      setLoading(true);
      const isActive = () => abortRef.current === controller;
      api.exportVault.preview(nextSel, { signal: controller.signal })
        .then((data) => {
          if (!isActive()) return;
          setPreview(data);
          setError(null);
          setDenied(false);
        })
        .catch((err) => {
          if (err?.name === 'AbortError' || !isActive()) return;
          if (err?.status === 403) {
            setDenied(true);
          } else {
            console.warn('ExportDialog: preview request failed', err);
            setError(err?.message || 'Failed to load export preview.');
          }
        })
        .finally(() => { if (isActive()) setLoading(false); });
    }, DEBOUNCE_MS);
  }, []);

  // Reset the selection whenever the dialog transitions to open — derived
  // during render (store-previous-and-compare, NewArticleModal.jsx:16-26). The
  // UNSET sentinel (not `isOpen`'s own type) forces this branch on the very
  // first render even when the dialog is mounted already-open, not just on a
  // later false→true transition. Only plain setState calls here — scheduling
  // the initial preview (which reads refs) happens in the effect below instead,
  // since refs may not be read during render (react-hooks/refs).
  const [prevIsOpen, setPrevIsOpen] = useState(UNSET);
  if (isOpen !== prevIsOpen) {
    setPrevIsOpen(isOpen);
    if (isOpen) {
      setSel(initialSelection(initialCluster));
      setPreview(null);
      setError(null);
      setDenied(false);
    }
  }

  // Trigger the initial preview for the reset selection whenever the dialog
  // opens (fires on this transition and also on an already-open first mount,
  // since effects always run after the initial render regardless of the
  // "previous" value).
  useEffect(() => {
    if (!isOpen) return;
    schedulePreview(initialSelection(initialCluster));
  }, [isOpen, initialCluster, schedulePreview]);

  // Load the cluster/tag picker options once per open (promise-chain +
  // cancelled flag, BacklinksPanel.jsx:13-20).
  useEffect(() => {
    if (!isOpen) return;
    let cancelled = false;
    api.exportVault.options()
      .then((data) => { if (!cancelled) setOptions(data || { clusters: [], tags: [] }); })
      .catch((err) => {
        if (!cancelled) console.warn('ExportDialog: failed to load export options', err);
      });
    return () => { cancelled = true; };
  }, [isOpen]);

  useEffect(() => () => clearTimeout(debounceRef.current), []);

  if (!isOpen) return null;

  const update = (patch) => {
    const next = { ...sel, ...patch };
    setSel(next);
    // The current preview is stale from this moment — keep Download disabled
    // through the debounce window, not just while the request is in flight.
    setLoading(true);
    schedulePreview(next);
  };

  const disabledReason = (() => {
    if (!preview) return 'Loading preview…';
    if (preview.pages === 0) return 'Nothing matches this selection';
    if (preview.overCap) return `${preview.pages} pages exceeds the limit of ${preview.cap} — narrow the selection`;
    if (loading) return 'Updating preview…';
    return null;
  })();

  const sample = preview?.sample || [];
  const shownSample = sample.slice(0, 10);
  const remaining = preview ? Math.max(0, (preview.pages || 0) - shownSample.length) : 0;

  return (
    <Modal isOpen={isOpen} onClose={onClose} labelledBy="export-dialog-title" className="search-dialog export-dialog" testId="export-dialog">
      <h2 id="export-dialog-title" style={{ fontFamily: 'var(--font-display)', fontSize: '1.5rem', marginBottom: 'var(--space-lg)', textAlign: 'center' }}>
        Export to Obsidian
      </h2>

      {denied ? (
        <>
          <p data-testid="export-denied">Export is disabled for your account.</p>
          <div className="modal-actions">
            <button type="button" className="btn btn-ghost" onClick={onClose}>Close</button>
          </div>
        </>
      ) : (
        <>
          {error && <div className="error-banner" style={{ marginBottom: 'var(--space-md)' }}>{error}</div>}

          <div className="export-dialog-controls">
            <div>
              <label className="field-label" htmlFor="export-clusters">Clusters</label>
              <TagInput
                id="export-clusters"
                value={sel.clusters}
                onChange={(clusters) => update({ clusters })}
                suggestions={options.clusters}
                placeholder="Add cluster…"
              />
              <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-xs)', marginTop: 'var(--space-xs)', fontSize: '0.85rem' }}>
                <input
                  type="checkbox"
                  checked={sel.subclusters}
                  onChange={(e) => update({ subclusters: e.target.checked })}
                />
                Include sub-clusters
              </label>
            </div>

            <div>
              <label className="field-label" htmlFor="export-tags">Tags</label>
              <TagInput
                id="export-tags"
                value={sel.tags}
                onChange={(tags) => update({ tags })}
                suggestions={options.tags}
                placeholder="Add tag…"
              />
            </div>

            <div>
              <label className="field-label" htmlFor="export-type">Type</label>
              <Select
                id="export-type"
                value={sel.type}
                options={TYPE_OPTIONS}
                onChange={(type) => update({ type })}
                placeholder="Any type"
                ariaLabel="Type"
                data-testid="export-type"
              />
            </div>

            <div>
              <label className="field-label" htmlFor="export-status">Status</label>
              <input
                id="export-status"
                type="text"
                className="form-input"
                value={sel.status}
                onChange={(e) => update({ status: e.target.value })}
              />
            </div>

            <div>
              <div className="field-label">Hops from the selection</div>
              <div style={{ display: 'flex', gap: 'var(--space-xs)' }}>
                {[0, 1, 2].map((h) => (
                  <button
                    key={h}
                    type="button"
                    aria-pressed={sel.hops === h}
                    onClick={() => update({ hops: h })}
                    style={{
                      flex: 1,
                      padding: 'var(--space-xs) var(--space-sm)',
                      border: '1px solid var(--border)',
                      borderRadius: 'var(--radius-sm)',
                      cursor: 'pointer',
                      fontSize: '0.85rem',
                      fontWeight: 500,
                      background: sel.hops === h ? 'var(--accent)' : 'var(--bg-elevated)',
                      color: sel.hops === h ? 'white' : 'var(--text)',
                    }}
                  >
                    {h}
                  </button>
                ))}
              </div>
            </div>

            <div>
              <div className="field-label">Unresolved links</div>
              <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-xs)', fontSize: '0.85rem' }}>
                <input
                  type="radio"
                  name="export-unresolved"
                  checked={sel.unresolved === 'keep'}
                  onChange={() => update({ unresolved: 'keep' })}
                />
                Keep as [[links]]
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-xs)', fontSize: '0.85rem' }}>
                <input
                  type="radio"
                  name="export-unresolved"
                  checked={sel.unresolved === 'url'}
                  onChange={() => update({ unresolved: 'url' })}
                />
                Link to the wiki
              </label>
            </div>
          </div>

          {preview && (
            <div className="export-dialog-summary" data-testid="export-preview-summary">
              {preview.pages} pages · up to {preview.attachments} attachments · ~{formatMB(preview.estimatedBytes)} MB · {preview.unresolvedLinks} links unresolved
            </div>
          )}

          {shownSample.length > 0 && (
            <ul className="export-dialog-sample">
              {shownSample.map((name) => (
                <li key={name}>{name}</li>
              ))}
              {remaining > 0 && <li>and {remaining} more</li>}
            </ul>
          )}

          <div className="modal-actions">
            <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
            {disabledReason ? (
              <button type="button" className="btn btn-primary" disabled data-testid="export-disabled-reason">
                {disabledReason}
              </button>
            ) : (
              <a className="btn btn-primary" href={api.exportVault.downloadUrl(sel)} download data-testid="export-download">
                Download vault (.zip)
              </a>
            )}
          </div>
        </>
      )}
    </Modal>
  );
}
