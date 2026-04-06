import { useState } from 'react';
import ProposalReviewQueue from './ProposalReviewQueue';
import GraphExplorer from './GraphExplorer';
import EdgeExplorer from './EdgeExplorer';
import KgEmbeddingsTab from './KgEmbeddingsTab';
import ContentEmbeddingsTab from './ContentEmbeddingsTab';
import '../../styles/admin.css';

const TABS = [
  { id: 'proposals', label: 'Proposals' },
  { id: 'node-explorer', label: 'Node Explorer' },
  { id: 'edge-explorer', label: 'Edge Explorer' },
  { id: 'kg-embeddings', label: 'KG Embeddings' },
  { id: 'content-embeddings', label: 'Content Embeddings' },
];

export default function AdminKnowledgePage() {
  const [activeTab, setActiveTab] = useState('proposals');

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
      </div>
      {activeTab === 'proposals' && <ProposalReviewQueue />}
      {activeTab === 'node-explorer' && <GraphExplorer />}
      {activeTab === 'edge-explorer' && <EdgeExplorer />}
      {activeTab === 'kg-embeddings' && <KgEmbeddingsTab />}
      {activeTab === 'content-embeddings' && <ContentEmbeddingsTab />}
    </div>
  );
}
