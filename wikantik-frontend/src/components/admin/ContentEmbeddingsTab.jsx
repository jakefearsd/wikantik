import { useState } from 'react';
import { api } from '../../api/client';
import { useAdminResource } from '../../hooks/useAdminResource';
import PageEditLink from './PageEditLink';
import EmptyState from '../ui/EmptyState';
import ConfirmDialog from '../ui/ConfirmDialog';

const NO_FM_LIMIT = 50;

export default function ContentEmbeddingsTab() {
  // The status and the first page of the no-frontmatter list load together; reload() (after a backfill) is silent.
  const { data, loading, error: loadError, reload } = useAdminResource(
    () => api.admin.embeddings.getStatus().then(async (st) => {
      const fm = await api.admin.embeddings.getPagesWithoutFrontmatter(NO_FM_LIMIT, 0);
      return { status: st, pages: fm.pages || [], total: fm.total || 0 };
    }),
  );
  const [noFmOffset, setNoFmOffset] = useState(0);
  // A later page of the list; the first page lives in `data`.
  const [laterPage, setLaterPage] = useState(null);
  const [backfilling, setBackfilling] = useState(false);
  const [backfillStatus, setBackfillStatus] = useState(null);
  const [actionError, setActionError] = useState(null);
  const [confirmingBackfill, setConfirmingBackfill] = useState(false);

  const error = loadError || actionError;
  const status = data?.status ?? null;
  const shown = noFmOffset > 0 && laterPage ? laterPage : data;
  const noFmPages = shown?.pages ?? [];
  const noFmTotal = shown?.total ?? 0;

  const goToOffset = async (offset) => {
    setNoFmOffset(offset);
    if (offset === 0) {
      setLaterPage(null);
      return;
    }
    try {
      const fm = await api.admin.embeddings.getPagesWithoutFrontmatter(NO_FM_LIMIT, offset);
      setLaterPage({ pages: fm.pages || [], total: fm.total || 0 });
    } catch (err) {
      setActionError(err.message);
    }
  };

  const refreshAfterBackfill = async () => {
    setNoFmOffset(0);
    setLaterPage(null);
    await reload();
  };

  const handleBackfill = async () => {
    setConfirmingBackfill(false);
    setBackfilling(true);
    setActionError(null);
    try {
      await api.admin.embeddings.backfillFrontmatter();
      const poll = setInterval(async () => {
        try {
          const st = await api.admin.embeddings.getBackfillStatus();
          setBackfillStatus(st);
          if (!st.running) {
            clearInterval(poll);
            setBackfilling(false);
            await refreshAfterBackfill();
          }
        } catch (err) {
          clearInterval(poll);
          setBackfilling(false);
          setActionError(err.message);
        }
      }, 2000);
    } catch (err) {
      setBackfilling(false);
      setActionError(err.message);
    }
  };

  if (loading) return <div className="admin-loading">Loading content embeddings...</div>;
  if (error) return <div className="admin-error">{error}</div>;

  const coverage = status?.chunksTotal > 0
    ? Math.round((status.chunksEmbedded / status.chunksTotal) * 100)
    : 0;

  return (
    <div>
      <div style={{ padding: 'var(--space-sm)', background: 'var(--bg-sidebar)', borderRadius: 'var(--radius-md)', marginBottom: 'var(--space-md)', fontSize: '0.85em' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-md)', flexWrap: 'wrap' }}>
          <span><strong>Embedding index:</strong> {status?.ready ? 'Ready' : 'Not populated'}</span>
          {status?.ready && (
            <>
              <span>{status.model}</span>
              <span>dim {status.dimension}</span>
              <span>{status.pagesEmbedded} pages embedded</span>
              <span>{`${status.chunksEmbedded} / ${status.chunksTotal} chunks embedded (${coverage}%)`}</span>
            </>
          )}
        </div>
      </div>

      {noFmTotal > 0 && (
        <div style={{ marginBottom: 'var(--space-md)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', marginBottom: 'var(--space-sm)' }}>
            <h4 style={{ fontSize: '0.95em', margin: 0 }}>
              Pages Without Frontmatter <span style={{ color: 'var(--text-muted)', fontWeight: 'normal' }}>({noFmTotal})</span>
            </h4>
            <button className="btn btn-primary btn-sm" onClick={() => setConfirmingBackfill(true)} disabled={backfilling}>
              {backfilling ? `Backfilling... ${backfillStatus ? `(${backfillStatus.processed}/${backfillStatus.total})` : ''}` : 'Backfill Frontmatter'}
            </button>
          </div>
          <div style={{ maxHeight: '300px', overflowY: 'auto', border: '1px solid var(--border)', borderRadius: 'var(--radius-md)' }}>
            <table className="admin-table">
              <thead>
                <tr>
                  <th>#</th>
                  <th>Page Name</th>
                  <th>Last Modified</th>
                </tr>
              </thead>
              <tbody>
                {noFmPages.map((p, i) => (
                  <tr key={p.name}>
                    <td style={{ color: 'var(--text-muted)', width: '40px' }}>{noFmOffset + i + 1}</td>
                    <td>
                      <PageEditLink name={p.name} />
                    </td>
                    <td style={{ fontSize: '0.85em', color: 'var(--text-muted)' }}>
                      {p.lastModified ? new Date(p.lastModified).toLocaleString() : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {noFmTotal > NO_FM_LIMIT && (
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 'var(--space-sm)', fontSize: '0.85em' }}>
              <button className="btn btn-sm" onClick={() => goToOffset(Math.max(0, noFmOffset - NO_FM_LIMIT))} disabled={noFmOffset === 0}>Prev</button>
              <span>Showing {noFmOffset + 1}–{noFmOffset + noFmPages.length} of {noFmTotal}</span>
              <button className="btn btn-sm" onClick={() => goToOffset(noFmOffset + NO_FM_LIMIT)} disabled={noFmOffset + noFmPages.length >= noFmTotal}>Next</button>
            </div>
          )}
        </div>
      )}

      {noFmTotal === 0 && (
        <EmptyState message="Every page has frontmatter — nothing to backfill." />
      )}

      {confirmingBackfill && (
        <ConfirmDialog
          title="Backfill Frontmatter"
          message="This will generate default frontmatter for all pages that lack it. Continue?"
          confirmLabel="Continue"
          danger={false}
          onConfirm={handleBackfill}
          onCancel={() => setConfirmingBackfill(false)}
        />
      )}
    </div>
  );
}
