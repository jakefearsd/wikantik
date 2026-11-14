import { useState } from 'react';
import ContentEmbeddingsTab from './ContentEmbeddingsTab';
import EmbeddingActivityTab from './EmbeddingActivityTab';
import PageHeader from './PageHeader';
import '../../styles/admin.css';

const TABS = [
  { id: 'content-embeddings', label: 'Content embeddings',
    description: 'Inspect the embedding index that powers hybrid search and similar pages: model, dimension and chunk coverage. Identify pages missing frontmatter and bulk-backfill it.' },
  { id: 'embedding-activity', label: 'Embedding activity',
    description: 'Live view of recent and in-flight LLM calls (embeddings) for roughly the last hour. Held in memory only; cleared on restart.' },
];

export default function AdminEmbeddingsPage() {
  const [activeTab, setActiveTab] = useState('content-embeddings');

  return (
    <div className="admin-embeddings page-enter">
      <PageHeader
        title="Embeddings"
        description="Embedding index health and LLM call activity."
      />
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
      <p className="tab-description">
        {TABS.find(t => t.id === activeTab)?.description}
      </p>
      <div data-testid={`embeddings-tab-panel-${activeTab}`}>
        {activeTab === 'content-embeddings' && <ContentEmbeddingsTab />}
        {activeTab === 'embedding-activity' && <EmbeddingActivityTab />}
      </div>
    </div>
  );
}
