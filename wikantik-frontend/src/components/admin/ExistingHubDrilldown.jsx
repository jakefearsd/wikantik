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
import PageLink from './PageLink';

function fmtCos(v) {
  return v == null || Number.isNaN(v) ? '—' : v.toFixed(2);
}

/**
 * Pure presentational drilldown view for a single hub. Sections are hidden when empty.
 *
 * Props:
 *   drilldown        — HubDrilldown record from the API
 *   onRemoveMember   — (hubName, member) => void
 *   removingMember   — string | null  (the member name currently being removed; row disabled)
 */
export default function ExistingHubDrilldown({ drilldown, onRemoveMember, removingMember }) {
  if (!drilldown) return null;
  const hub = drilldown;
  const cannotRemove = (hub.members?.length ?? 0) <= 2;

  return (
    <div
      className="hub-overview-drilldown"
      data-testid={`existing-hub-drilldown-${hub.name}`}
      style={{
        background: 'var(--bg-elevated)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
        padding: 'var(--space-md)',
        marginTop: 'var(--space-sm)',
      }}
    >
      <div style={{ marginBottom: 'var(--space-sm)', fontSize: '0.85rem', color: 'var(--text-muted)' }}>
        coherence: <strong>{fmtCos(hub.coherence)}</strong>
        {!hub.hasBackingPage && (
          <span style={{ marginLeft: 'var(--space-md)', color: 'var(--color-warning)' }}>
            (orphan — no backing page)
          </span>
        )}
      </div>

      {/* Members table */}
      <h4 style={{ margin: '0 0 var(--space-xs) 0' }}>Members</h4>
      <table className="admin-table">
        <thead>
          <tr>
            <th>Name</th>
            <th>Cosine</th>
            <th style={{ width: '90px' }}>Actions</th>
          </tr>
        </thead>
        <tbody>
          {hub.members.map((m) => (
            <tr key={m.name} data-testid={`existing-hub-member-${hub.name}-${m.name}`}>
              <td><PageLink name={m.name} /></td>
              <td style={{ fontVariantNumeric: 'tabular-nums' }}>{fmtCos(m.cosineToCentroid)}</td>
              <td>
                <button
                  className="btn btn-sm btn-danger"
                  onClick={() => onRemoveMember(hub.name, m.name)}
                  disabled={cannotRemove || removingMember === m.name}
                  title={cannotRemove ? 'Cannot remove — would leave fewer than 2 members' : ''}
                  data-testid={`existing-hub-member-remove-${hub.name}-${m.name}`}
                >
                  Remove
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {/* Stub members callout */}
      {hub.stubMembers?.length > 0 && (
        <div
          style={{
            marginTop: 'var(--space-md)',
            padding: 'var(--space-sm)',
            background: 'var(--bg-warning-subtle, #fff7e6)',
            border: '1px solid var(--color-warning, #f0b50b)',
            borderRadius: 'var(--radius-sm)',
            fontSize: '0.85rem',
          }}
          data-testid={`existing-hub-stubs-${hub.name}`}
        >
          <strong>Stub members (no wiki page):</strong>{' '}
          {hub.stubMembers.map((s) => s.name).join(', ')}
        </div>
      )}

      {/* Near-miss TF-IDF */}
      {hub.nearMissTfidf?.length > 0 && (
        <div style={{ marginTop: 'var(--space-md)' }} data-testid={`existing-hub-nearmiss-${hub.name}`}>
          <h4 style={{ margin: '0 0 var(--space-xs) 0' }}>Near-Miss (TF-IDF)</h4>
          <ul>
            {hub.nearMissTfidf.map((n) => (
              <li key={n.name}>
                <PageLink name={n.name} /> — {fmtCos(n.cosineToCentroid)}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* MoreLikeThis (Lucene) */}
      {hub.moreLikeThisLucene?.length > 0 && (
        <div style={{ marginTop: 'var(--space-md)' }} data-testid={`existing-hub-mlt-${hub.name}`}>
          <h4 style={{ margin: '0 0 var(--space-xs) 0' }}>MoreLikeThis (Lucene)</h4>
          <ul>
            {hub.moreLikeThisLucene.map((m) => (
              <li key={m.name}>
                <PageLink name={m.name} /> — score {m.luceneScore?.toFixed?.(2) ?? '—'}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* Overlap hubs */}
      {hub.overlapHubs?.length > 0 && (
        <div style={{ marginTop: 'var(--space-md)' }} data-testid={`existing-hub-overlap-${hub.name}`}>
          <h4 style={{ margin: '0 0 var(--space-xs) 0' }}>Overlap Hubs</h4>
          <ul>
            {hub.overlapHubs.map((o) => (
              <li key={o.name}>
                <PageLink name={o.name} /> — cosine {fmtCos(o.centroidCosine)},
                {' '}{o.sharedMemberCount} shared member(s)
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
