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
import { Fragment, useRef, useState } from 'react';
import { api } from '../../api/client';
import ExistingHubDrilldown from './ExistingHubDrilldown';

/**
 * Container component for the "Existing Hubs" admin panel. Mounted by
 * HubDiscoveryTab above the dismissed-proposals panel.
 *
 * State model:
 *   - expanded:        is the panel open?
 *   - loaded:          have we ever fetched the list?
 *   - hubs[]:          summary rows from the API
 *   - openHubs:        Set<string> — hub names whose drilldown is currently expanded
 *   - drilldowns:      Map<string, HubDrilldown> — cached drilldowns
 *   - drilldownLoading:Set<string>
 *   - confirm:         { hubName, member } | null  — pending remove-member confirmation
 *   - removingMember:  string | null — member currently being removed
 */
export default function ExistingHubsPanel({ onError }) {
  const [expanded, setExpanded] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [hubs, setHubs] = useState([]);
  const [openHubs, setOpenHubs] = useState(() => new Set());
  const [drilldowns, setDrilldowns] = useState(() => new Map());
  const [drilldownLoading, setDrilldownLoading] = useState(() => new Set());
  const [confirm, setConfirm] = useState(null);
  const [removingMember, setRemovingMember] = useState(null);

  // Monotonic per-hub drilldown request id, mirroring the dismissed-list pattern
  // in HubDiscoveryTab. Prevents an old in-flight drilldown response from
  // overwriting a newer one when the admin dismisses members in quick succession.
  const drilldownRequestIdRef = useRef(new Map());

  const loadList = async () => {
    setLoading(true);
    try {
      const resp = await api.knowledge.listExistingHubs();
      setHubs(resp.hubs || []);
      setLoaded(true);
    } catch (err) {
      onError?.(err.message || 'Failed to load existing hubs');
    } finally {
      setLoading(false);
    }
  };

  const toggle = async () => {
    if (!expanded && !loaded) await loadList();
    setExpanded((v) => !v);
  };

  const loadDrilldown = async (hubName) => {
    const nextId = (drilldownRequestIdRef.current.get(hubName) || 0) + 1;
    drilldownRequestIdRef.current.set(hubName, nextId);
    setDrilldownLoading((prev) => new Set(prev).add(hubName));
    try {
      const d = await api.knowledge.getHubDrilldown(hubName);
      if (drilldownRequestIdRef.current.get(hubName) !== nextId) return; // stale
      setDrilldowns((prev) => {
        const next = new Map(prev);
        next.set(hubName, d);
        return next;
      });
    } catch (err) {
      if (drilldownRequestIdRef.current.get(hubName) !== nextId) return;
      onError?.(err.message || `Failed to load drilldown for ${hubName}`);
    } finally {
      setDrilldownLoading((prev) => {
        const next = new Set(prev);
        next.delete(hubName);
        return next;
      });
    }
  };

  const toggleHubRow = async (hubName) => {
    const isOpen = openHubs.has(hubName);
    setOpenHubs((prev) => {
      const next = new Set(prev);
      if (isOpen) next.delete(hubName);
      else next.add(hubName);
      return next;
    });
    if (!isOpen && !drilldowns.has(hubName)) {
      await loadDrilldown(hubName);
    }
  };

  const requestRemoveMember = (hubName, member) => {
    setConfirm({ hubName, member });
  };

  const confirmRemove = async () => {
    if (!confirm) return;
    const { hubName, member } = confirm;
    setConfirm(null);
    setRemovingMember(member);
    try {
      await api.knowledge.removeHubMember(hubName, member);
      // Optimistic: drop the row from the cached drilldown immediately.
      setDrilldowns((prev) => {
        const cur = prev.get(hubName);
        if (!cur) return prev;
        const next = new Map(prev);
        next.set(hubName, {
          ...cur,
          members: cur.members.filter((m) => m.name !== member),
        });
        return next;
      });
      // Background reconcile to pick up new coherence and near-miss counts.
      loadDrilldown(hubName);
      loadList();
    } catch (err) {
      onError?.(err.message || `Failed to remove ${member} from ${hubName}`);
    } finally {
      setRemovingMember(null);
    }
  };

  const chevron = expanded ? '▾' : '▸';

  return (
    <div style={{ marginBottom: 'var(--space-md)' }}>
      <button
        type="button"
        onClick={toggle}
        data-testid="existing-hubs-toggle"
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
        Existing Hubs ({hubs.length}) {chevron}
      </button>
      {expanded && (
        <div
          style={{
            marginTop: 'var(--space-sm)',
            background: 'var(--bg-elevated)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--radius-md)',
            padding: 'var(--space-md)',
          }}
          data-testid="existing-hubs-panel"
        >
          {loading ? (
            <p>Loading…</p>
          ) : hubs.length === 0 ? (
            <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem' }}>
              No hubs exist yet. Accept a Hub Discovery proposal to create one.
            </p>
          ) : (
            <table className="admin-table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Members</th>
                  <th>Inbound</th>
                  <th>Near-Miss</th>
                </tr>
              </thead>
              <tbody>
                {hubs.map((h) => (
                  <Fragment key={h.name}>
                    <tr
                      onClick={() => toggleHubRow(h.name)}
                      data-testid={`existing-hub-row-${h.name}`}
                      style={{ cursor: 'pointer' }}
                    >
                      <td>
                        {h.hasBackingPage ? h.name : (
                          <>
                            <span>{h.name}</span>
                            <span style={{ marginLeft: 'var(--space-xs)', color: 'var(--color-warning)', fontSize: '0.75rem' }}>
                              orphan
                            </span>
                          </>
                        )}
                      </td>
                      <td>{h.memberCount}</td>
                      <td>{h.inboundLinkCount}</td>
                      <td>{h.nearMissCount}</td>
                    </tr>
                    {openHubs.has(h.name) && (
                      <tr>
                        <td colSpan={4} style={{ padding: 0, background: 'transparent' }}>
                          {drilldownLoading.has(h.name) ? (
                            <p style={{ padding: 'var(--space-sm)' }}>Loading…</p>
                          ) : drilldowns.has(h.name) ? (
                            <ExistingHubDrilldown
                              drilldown={drilldowns.get(h.name)}
                              onRemoveMember={requestRemoveMember}
                              removingMember={removingMember}
                            />
                          ) : null}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      {confirm && (
        <div
          className="modal-overlay"
          onClick={() => setConfirm(null)}
          data-testid="existing-hub-member-remove-confirm-modal"
        >
          <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
            <h3 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>
              Remove Hub Member
            </h3>
            <p>
              Remove <strong>{confirm.member}</strong> from <strong>{confirm.hubName}</strong>?
              The hub page will be re-saved without this member, and the kg_edges
              relationship will be dropped automatically.
            </p>
            <div className="modal-actions" style={{ marginTop: 'var(--space-lg)' }}>
              <button
                className="btn btn-ghost"
                onClick={() => setConfirm(null)}
                data-testid="existing-hub-member-remove-confirm-cancel"
              >
                Cancel
              </button>
              <button
                className="btn btn-primary btn-danger"
                onClick={confirmRemove}
                data-testid="existing-hub-member-remove-confirm-ok"
              >
                Remove
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
