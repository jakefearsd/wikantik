/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/client';
import HubDiscoveryCard from './HubDiscoveryCard';
import ExistingHubsPanel from './ExistingHubsPanel';

export default function HubDiscoveryTab() {
  const [proposals, setProposals] = useState([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [running, setRunning] = useState(false);
  const [toast, setToast] = useState(null);

  // Dismissed section state
  const [dismissedExpanded, setDismissedExpanded] = useState(false);
  const [dismissed, setDismissed] = useState([]);
  const [dismissedTotal, setDismissedTotal] = useState(0);
  const [dismissedLoading, setDismissedLoading] = useState(false);
  const [dismissedLoaded, setDismissedLoaded] = useState(false);
  const [selected, setSelected] = useState(new Set());
  const [confirm, setConfirm] = useState(null); // { kind: 'single' | 'bulk', ids: number[] }

  // Monotonic request id for the dismissed-list fetch. Only the most recent
  // in-flight request is allowed to commit its response to state; earlier ones
  // are discarded. Protects against out-of-order responses when the user
  // dismisses multiple cards in quick succession.
  const dismissedRequestIdRef = useRef(0);

  const load = async () => {
    setLoading(true);
    try {
      const resp = await api.knowledge.listHubDiscoveryProposals(50, 0);
      setProposals(resp.proposals || []);
      setTotal(resp.total || 0);
    } catch (err) {
      setToast({ kind: 'error', message: err.message || 'Load failed' });
    } finally {
      setLoading(false);
    }
  };

  // Loads the dismissed-proposals list. Pass `{ silent: true }` to reconcile
  // in the background without flashing the "Loading…" state — used by the
  // optimistic-insert path so the already-rendered table stays visible.
  const loadDismissed = async ({ silent = false } = {}) => {
    const requestId = ++dismissedRequestIdRef.current;
    if (!silent) setDismissedLoading(true);
    try {
      const resp = await api.knowledge.listDismissedHubDiscoveryProposals(50, 0);
      if (requestId !== dismissedRequestIdRef.current) return; // stale response
      setDismissed(resp.proposals || []);
      setDismissedTotal(resp.total || 0);
      setDismissedLoaded(true);
    } catch (err) {
      if (requestId !== dismissedRequestIdRef.current) return;
      setToast({ kind: 'error', message: err.message || 'Load dismissed failed' });
    } finally {
      if (requestId === dismissedRequestIdRef.current && !silent) {
        setDismissedLoading(false);
      }
    }
  };

  useEffect(() => { load(); }, []);

  const toggleDismissed = async () => {
    if (!dismissedExpanded && !dismissedLoaded) {
      await loadDismissed();
    }
    setDismissedExpanded(v => !v);
  };

  const handleRun = async () => {
    setRunning(true);
    setToast(null);
    try {
      const resp = await api.knowledge.runHubDiscovery();
      const skipped = resp.skippedDismissed || 0;
      setToast({
        kind: 'success',
        message: `Discovery complete: ${resp.proposalsCreated} proposals from ${resp.candidatePoolSize} candidates (${resp.noisePages} noise, ${skipped} skipped as previously dismissed) in ${resp.durationMs} ms`,
      });
      await load();
      if (dismissedLoaded) {
        await loadDismissed();
      }
    } catch (err) {
      setToast({ kind: 'error', message: err.message || 'Run failed' });
    } finally {
      setRunning(false);
    }
  };

  // Accept removes the proposal from the system entirely — no dismissed-bucket impact.
  const handleAccepted = (id) => {
    setProposals((prev) => prev.filter((p) => p.id !== id));
    setTotal((prev) => Math.max(0, prev - 1));
  };

  // Dismiss moves the proposal into the dismissed bucket. We optimistically
  // insert a placeholder row (so the count badge and, if expanded, the table
  // update instantly) and then reconcile against the server in the background
  // without a visible loading flash. The reconcile is unconditional — we want
  // the count honest even when the panel is collapsed, so expanding it later
  // shows accurate state without a round-trip.
  const handleDismissed = (proposal) => {
    setProposals((prev) => prev.filter((p) => p.id !== proposal.id));
    setTotal((prev) => Math.max(0, prev - 1));

    const placeholder = {
      id: proposal.id,
      suggestedName: proposal.suggestedName,
      exemplarPage: proposal.exemplarPage,
      memberPages: proposal.memberPages,
      reviewedBy: null, // renders as '—' until the reconcile supplies the real reviewer
      reviewedAt: new Date().toISOString(),
    };
    setDismissed((prev) => [placeholder, ...prev.filter((r) => r.id !== proposal.id)]);
    setDismissedTotal((prev) => prev + 1);

    loadDismissed({ silent: true });
  };

  const handleCardError = (message) => {
    setToast({ kind: 'error', message });
  };

  const toggleSelect = (id) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleSelectAll = () => {
    if (selected.size === dismissed.length) {
      setSelected(new Set());
    } else {
      setSelected(new Set(dismissed.map(p => p.id)));
    }
  };

  const reloadAfterDelete = async () => {
    setSelected(new Set());
    await loadDismissed();
    await load();
  };

  const handleDeleteSingle = (id) => {
    setConfirm({ kind: 'single', ids: [id] });
  };

  const handleBulkDeleteClick = () => {
    if (selected.size === 0) return;
    setConfirm({ kind: 'bulk', ids: [...selected] });
  };

  const confirmDelete = async () => {
    if (!confirm) return;
    const { kind, ids } = confirm;
    setConfirm(null);
    try {
      if (kind === 'single') {
        await api.knowledge.deleteDismissedHubDiscoveryProposal(ids[0]);
        setToast({ kind: 'success', message: 'Dismissed proposal deleted' });
      } else {
        const resp = await api.knowledge.bulkDeleteDismissedHubDiscoveryProposals(ids);
        setToast({ kind: 'success', message: `Deleted ${resp.deleted} dismissed proposal(s)` });
      }
      await reloadAfterDelete();
    } catch (err) {
      setToast({ kind: 'error', message: err.message || 'Delete failed' });
    }
  };

  const chevron = dismissedExpanded ? '▾' : '▸';

  return (
    <div className="hub-discovery-tab" data-testid="hub-discovery-tab">
      {/* Existing hubs — expandable section, sits above dismissed proposals */}
      <ExistingHubsPanel onError={(message) => setToast({ kind: 'error', message })} />

      {/* Dismissed proposals — expandable section */}
      <div style={{ marginBottom: 'var(--space-md)' }}>
        <button
          type="button"
          onClick={toggleDismissed}
          data-testid="hub-discovery-dismissed-toggle"
          style={{
            color: 'var(--text-muted)',
            fontSize: '0.85rem',
            cursor: 'pointer',
            background: 'none',
            border: 'none',
            padding: '0',
            userSelect: 'none',
          }}
        >
          Dismissed Proposals ({dismissedTotal}) {chevron}
        </button>
        {dismissedExpanded && (
          <div
            style={{
              marginTop: 'var(--space-sm)',
              background: 'var(--bg-elevated)',
              border: '1px solid var(--border)',
              borderRadius: 'var(--radius-md)',
              padding: 'var(--space-md)',
            }}
            data-testid="hub-discovery-dismissed-panel"
          >
            {dismissedLoading ? (
              <p>Loading…</p>
            ) : dismissed.length === 0 ? (
              <p data-testid="hub-discovery-dismissed-empty" style={{ color: 'var(--text-muted)', fontSize: '0.875rem' }}>
                No dismissed proposals retained.
              </p>
            ) : (
              <>
                <div style={{ display: 'flex', gap: 'var(--space-sm)', marginBottom: 'var(--space-sm)', fontSize: '0.85em' }}>
                  <button
                    className="btn btn-sm btn-danger"
                    onClick={handleBulkDeleteClick}
                    disabled={selected.size === 0}
                    data-testid="hub-discovery-dismissed-bulk-delete"
                  >
                    Delete Selected ({selected.size})
                  </button>
                </div>
                <table className="admin-table">
                  <thead>
                    <tr>
                      <th style={{ width: '30px' }}>
                        <input
                          type="checkbox"
                          checked={selected.size === dismissed.length && dismissed.length > 0}
                          onChange={toggleSelectAll}
                          data-testid="hub-discovery-dismissed-select-all"
                        />
                      </th>
                      <th>Suggested Name</th>
                      <th>Exemplar</th>
                      <th>Members</th>
                      <th>Reviewed By</th>
                      <th>Reviewed At</th>
                      <th style={{ width: '80px' }}>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {dismissed.map(p => (
                      <tr key={p.id} data-testid={`hub-discovery-dismissed-row-${p.id}`}>
                        <td>
                          <input
                            type="checkbox"
                            checked={selected.has(p.id)}
                            onChange={() => toggleSelect(p.id)}
                            data-testid={`hub-discovery-dismissed-select-${p.id}`}
                          />
                        </td>
                        <td>{p.suggestedName}</td>
                        <td>{p.exemplarPage}</td>
                        <td>{p.memberPages?.length ?? 0}</td>
                        <td>{p.reviewedBy || '—'}</td>
                        <td style={{ fontSize: '0.85em', color: 'var(--text-muted)' }}>
                          {p.reviewedAt ? new Date(p.reviewedAt).toLocaleString() : '—'}
                        </td>
                        <td>
                          <button
                            className="btn btn-sm btn-danger"
                            onClick={() => handleDeleteSingle(p.id)}
                            data-testid={`hub-discovery-dismissed-delete-${p.id}`}
                          >
                            Delete
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}
          </div>
        )}
      </div>

      <div className="hub-discovery-toolbar">
        <button
          className="btn btn-primary"
          onClick={handleRun}
          disabled={running}
          data-testid="hub-discovery-run"
        >
          {running ? 'Running…' : 'Run Discovery'}
        </button>
        <span className="hub-discovery-count" data-testid="hub-discovery-count">
          {total} pending
        </span>
      </div>
      {toast && (
        <div
          className={`toast toast-${toast.kind}`}
          data-testid={`hub-discovery-toast-${toast.kind}`}
        >
          {toast.message}
        </div>
      )}
      {loading ? (
        <p>Loading…</p>
      ) : proposals.length === 0 ? (
        <p data-testid="hub-discovery-empty">No pending cluster proposals. Click "Run Discovery" to generate.</p>
      ) : (
        <div className="hub-discovery-list">
          {proposals.map((p) => (
            <HubDiscoveryCard
              key={p.id}
              proposal={p}
              onAccepted={handleAccepted}
              onDismissed={handleDismissed}
              onError={handleCardError}
            />
          ))}
        </div>
      )}

      {confirm && (
        <div className="modal-overlay" onClick={() => setConfirm(null)} data-testid="hub-discovery-dismissed-confirm-modal">
          <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
            <h3 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>
              Delete Dismissed Proposal{confirm.ids.length > 1 ? 's' : ''}
            </h3>
            <p>
              {confirm.ids.length > 1
                ? `Are you sure you want to delete ${confirm.ids.length} dismissed proposals? They may be rediscovered the next time you run discovery.`
                : 'Are you sure you want to delete this dismissed proposal? It may be rediscovered the next time you run discovery.'}
            </p>
            <div className="modal-actions" style={{ marginTop: 'var(--space-lg)' }}>
              <button
                className="btn btn-ghost"
                onClick={() => setConfirm(null)}
                data-testid="hub-discovery-dismissed-confirm-cancel"
              >
                Cancel
              </button>
              <button
                className="btn btn-primary btn-danger"
                onClick={confirmDelete}
                data-testid="hub-discovery-dismissed-confirm-delete"
              >
                Delete
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
