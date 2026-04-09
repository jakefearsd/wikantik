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
import { useEffect, useState } from 'react';
import { api } from '../../api/client';
import HubDiscoveryCard from './HubDiscoveryCard';

export default function HubDiscoveryTab() {
  const [proposals, setProposals] = useState([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [running, setRunning] = useState(false);
  const [toast, setToast] = useState(null);

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

  useEffect(() => { load(); }, []);

  const handleRun = async () => {
    setRunning(true);
    setToast(null);
    try {
      const resp = await api.knowledge.runHubDiscovery();
      setToast({
        kind: 'success',
        message: `Discovery complete: ${resp.proposalsCreated} proposals from ${resp.candidatePoolSize} candidates (${resp.noisePages} noise) in ${resp.durationMs} ms`,
      });
      await load();
    } catch (err) {
      setToast({ kind: 'error', message: err.message || 'Run failed' });
    } finally {
      setRunning(false);
    }
  };

  const handleRemoved = (id) => {
    setProposals((prev) => prev.filter((p) => p.id !== id));
    setTotal((prev) => Math.max(0, prev - 1));
  };

  const handleCardError = (message) => {
    setToast({ kind: 'error', message });
  };

  return (
    <div className="hub-discovery-tab" data-testid="hub-discovery-tab">
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
              onRemoved={handleRemoved}
              onError={handleCardError}
            />
          ))}
        </div>
      )}
    </div>
  );
}
