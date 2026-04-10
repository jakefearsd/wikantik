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
import { useState } from 'react';
import { api } from '../../api/client';
import PageLink from './PageLink';

export default function HubDiscoveryCard({ proposal, onRemoved, onError }) {
  const [name, setName] = useState(proposal.suggestedName);
  const [checked, setChecked] = useState(() => new Set(proposal.memberPages));
  const [busy, setBusy] = useState(false);

  const toggle = (member) => {
    const next = new Set(checked);
    if (next.has(member)) next.delete(member);
    else next.add(member);
    setChecked(next);
  };

  const handleAccept = async () => {
    if (!name.trim()) {
      onError?.('Hub name must not be empty');
      return;
    }
    if (checked.size < 2) {
      onError?.('Select at least 2 members');
      return;
    }
    setBusy(true);
    try {
      const members = proposal.memberPages.filter((m) => checked.has(m));
      await api.knowledge.acceptHubDiscoveryProposal(proposal.id, name.trim(), members);
      onRemoved?.(proposal.id);
    } catch (err) {
      onError?.(err.body?.message || err.message || 'Accept failed');
      setBusy(false);
    }
  };

  const handleDismiss = async () => {
    setBusy(true);
    try {
      await api.knowledge.dismissHubDiscoveryProposal(proposal.id);
      onRemoved?.(proposal.id);
    } catch (err) {
      onError?.(err.body?.message || err.message || 'Dismiss failed');
      setBusy(false);
    }
  };

  return (
    <div className="hub-discovery-card" data-testid={`hub-discovery-card-${proposal.id}`}>
      <div className="hub-discovery-card-header">
        <input
          type="text"
          value={name}
          onChange={(e) => setName(e.target.value)}
          disabled={busy}
          data-testid={`hub-discovery-name-${proposal.id}`}
        />
        <div className="hub-discovery-card-meta">
          <span>exemplar: <strong><PageLink name={proposal.exemplarPage} /></strong></span>
          <span>coherence: {proposal.coherenceScore.toFixed(2)}</span>
        </div>
      </div>
      <ul className="hub-discovery-members">
        {proposal.memberPages.map((m) => (
          <li key={m} className="hub-discovery-member">
            <input
              type="checkbox"
              checked={checked.has(m)}
              onChange={() => toggle(m)}
              disabled={busy}
              data-testid={`hub-discovery-member-${proposal.id}-${m}`}
              aria-label={`Include ${m}`}
            />
            {' '}
            <PageLink name={m} />
          </li>
        ))}
      </ul>
      <div className="hub-discovery-card-actions">
        <button
          className="btn btn-primary"
          onClick={handleAccept}
          disabled={busy}
          data-testid={`hub-discovery-accept-${proposal.id}`}
        >
          Accept
        </button>
        <button
          className="btn"
          onClick={handleDismiss}
          disabled={busy}
          data-testid={`hub-discovery-dismiss-${proposal.id}`}
        >
          Dismiss
        </button>
      </div>
    </div>
  );
}
