// OverviewDashboard.jsx
import { useState, useEffect, useRef } from 'react';
import { api } from '../../api/client';
import PageHeader from './PageHeader';
import MetricCard from './MetricCard';
import '../../styles/admin.css';

const POLL_MS = 20000; // live cards (load, llmActivity) refresh on this cadence

// Each entry: how to render one card from the payload. `dim` marks the diagnostic band.
function statusCards(d) {
  return [
    { key: 'health',      label: 'Health',        render: (c) => <MetricCard label="Health" value={c?.status ?? '—'} meta={c?.version} degraded={!c} /> },
    { key: 'load',        label: 'Load',           render: (c) => <MetricCard label="Load" value={c ? `${c.inflight}/${c.permitsMax}` : null} meta={c ? `${c.rejected} shed` : null} degraded={!c} /> },
    { key: 'kgProposals', label: 'KG proposals',   render: (c) => <MetricCard label="KG proposals" value={c?.pending} meta="pending → review" accent to="/admin/knowledge-graph" degraded={!c} /> },
    { key: 'retrieval',   label: 'Retrieval',      render: (c) => <MetricCard label="Retrieval" value={c?.ndcg5} meta="nDCG@5" to="/admin/retrieval-quality" degraded={!c} /> },
    { key: 'llmActivity', label: 'LLM activity',   render: (c) => <MetricCard label="LLM activity" value={c?.inFlight} meta={c ? `${c.count}/${c.windowMinutes}m · ${c.errors} err` : null} accent degraded={!c} /> },
    { key: 'searchIndex', label: 'Search & index', render: (c) => <MetricCard label="Search & index" value={c?.indexable} meta={c ? `/${c.total} · ${c.embeddingsPct}% emb` : null} to="/admin/content" degraded={!c} /> },
    { key: 'users',       label: 'Users',          render: (c) => <MetricCard label="Users" value={c?.users} meta={c ? `${c.apiKeys} keys · ${c.locked} locked` : null} to="/admin/users" degraded={!c} /> },
    { key: 'recent',      label: 'Recent',         render: (c) => <MetricCard label="Recent" degraded={!c}>{c?.items && <ul className="metric-card-feed">{c.items.map((it, i) => <li key={i}>{it}</li>)}</ul>}</MetricCard> },
  ];
}

function metricCards(d) {
  return [
    { key: 'kgSize',          label: 'Knowledge Graph size', render: (c) => <MetricCard dim label="Knowledge Graph size" value={c?.nodes} meta={c ? `${c.edges} edges · ${c.stubs} stubs · ${c.orphans} orphans` : null} degraded={!c} /> },
    { key: 'extractor',       label: 'Extractor pipeline',   render: (c) => <MetricCard dim label="Extractor pipeline" value={c?.requests} meta={c ? `${c.triples} triples · ${c.failures} fail` : null} degraded={!c} /> },
    { key: 'judge',           label: 'KG judge',             render: (c) => <MetricCard dim label="KG judge" value={c?.pending} meta={c ? `${c.timeouts} timeout · ${c.shortCircuit} sc` : null} degraded={!c} /> },
    { key: 'renderCache',     label: 'Render cache',         render: (c) => <MetricCard dim label="Render cache" value={c?.hits} meta={c ? `${c.misses} miss · ${c.evictions} evict` : null} degraded={!c} /> },
    { key: 'auth',            label: 'Auth activity',        render: (c) => <MetricCard dim label="Auth activity" value={c?.logins} meta={c ? `${c.failed} failed` : null} degraded={!c} /> },
    { key: 'agentSurface',    label: 'Agent surface',        render: (c) => <MetricCard dim label="Agent surface" value={c?.hubSynthesis} meta={c ? `${c.forAgentBytes}B avg · ${c.hintFailures} hint fails` : null} degraded={!c} /> },
    { key: 'contentQuality',  label: 'Content quality',      render: (c) => <MetricCard dim label="Content quality" value={c?.authoritative} meta={c ? `${c.provisional} prov · ${c.stale} stale · ${c.noVerification} none` : null} degraded={!c} /> },
    { key: 'retrievalModes',  label: 'Retrieval modes',      render: (c) => <MetricCard dim label="Retrieval modes" value={c?.hybrid ?? c?.bm25} meta={c ? `bm25 ${c.bm25 ?? '—'} · hybrid ${c.hybrid ?? '—'} · graph ${c.hybridGraph ?? '—'}` : null} degraded={!c} /> },
    { key: 'attachments',     label: 'Attachments',          render: (c) => <MetricCard dim label="Attachments" value={c?.provider} meta={c ? `${c.maxSize} · ${c.allowedCount} allowed` : null} degraded={!c} /> },
  ];
}

export default function OverviewDashboard() {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const pollRef = useRef(null);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const res = await api.admin.getOverview();
        if (!cancelled) { setData(res || {}); setError(null); }
      } catch (e) {
        if (!cancelled) setError(e.message || 'Failed to load overview');
      }
    };
    load();
    pollRef.current = setInterval(load, POLL_MS);
    return () => { cancelled = true; clearInterval(pollRef.current); };
  }, []);

  if (error) return <div className="error-banner">{error}</div>;
  const d = data || {};
  // Honor both degradation signals: a card is degraded if its key is absent OR
  // listed in the server's `degraded` array (even when a partial object came back).
  const cards = { ...d };
  if (Array.isArray(d.degraded)) d.degraded.forEach((k) => { delete cards[k]; });

  return (
    <div className="dashboard page-enter" data-testid="admin-overview">
      <PageHeader title="Overview" description="Everything you administer, at a glance." />
      <div className="dashboard-section-title">Status &amp; action</div>
      <div className="dashboard-grid status">
        {statusCards(cards).map(({ key, render }) => (
          <div key={key} data-testid={`metric-card-${key}`}>{render(cards[key])}</div>
        ))}
      </div>
      <div className="dashboard-section-title">System metrics</div>
      <div className="dashboard-grid metrics">
        {metricCards(cards).map(({ key, render }) => (
          <div key={key} data-testid={`metric-card-${key}`}>{render(cards[key])}</div>
        ))}
      </div>
    </div>
  );
}
