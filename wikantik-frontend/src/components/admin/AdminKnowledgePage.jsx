import { useState } from 'react';
import { api } from '../../api/client';
import ProposalReviewQueue from './ProposalReviewQueue';
import GraphExplorer from './GraphExplorer';
import EdgeExplorer from './EdgeExplorer';
import KgEmbeddingsTab from './KgEmbeddingsTab';
import ContentEmbeddingsTab from './ContentEmbeddingsTab';
import HubProposalsTab from './HubProposalsTab';
import HubDiscoveryTab from './HubDiscoveryTab';
import '../../styles/admin.css';

const TABS = [
  { id: 'proposals', label: 'Proposals' },
  { id: 'node-explorer', label: 'Node Explorer' },
  { id: 'edge-explorer', label: 'Edge Explorer' },
  { id: 'kg-embeddings', label: 'KG Embeddings' },
  { id: 'content-embeddings', label: 'Content Embeddings' },
  { id: 'hub-proposals', label: 'Hub Proposals' },
  { id: 'hub-discovery', label: 'Hub Discovery' },
];

export default function AdminKnowledgePage() {
  const [activeTab, setActiveTab] = useState('proposals');
  const [clearing, setClearing] = useState(false);

  const handleClearAll = async () => {
    if (!confirm('Delete ALL knowledge graph data? This removes all nodes, edges, proposals, and embeddings.')) return;
    setClearing(true);
    try {
      await api.knowledge.clearAll();
      window.location.reload();
    } catch (err) {
      alert('Clear failed: ' + err.message);
    } finally {
      setClearing(false);
    }
  };

  return (
    <div className="admin-knowledge page-enter">
      <div className="admin-toolbar">
        <div className="admin-tabs">
          {TABS.map(tab => (
            <button
              key={tab.id}
              className={`admin-tab ${activeTab === tab.id ? 'active' : ''}`}
              onClick={() => setActiveTab(tab.id)}
            >
              {tab.label}
            </button>
          ))}
        </div>
        <button
          className="btn btn-sm"
          style={{ marginLeft: 'auto', color: 'var(--danger)' }}
          onClick={handleClearAll}
          disabled={clearing}
        >
          {clearing ? 'Clearing...' : 'Clear All'}
        </button>
      </div>
      {activeTab === 'proposals' && <ProposalReviewQueue />}
      {activeTab === 'node-explorer' && <GraphExplorer />}
      {activeTab === 'edge-explorer' && <EdgeExplorer />}
      {activeTab === 'kg-embeddings' && <KgEmbeddingsTab />}
      {activeTab === 'content-embeddings' && <ContentEmbeddingsTab />}
      {activeTab === 'hub-proposals' && <HubProposalsTab />}
      {activeTab === 'hub-discovery' && <HubDiscoveryTab />}
    </div>
  );
}
