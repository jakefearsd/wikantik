import { useState } from 'react';
import ProposalReviewQueue from './ProposalReviewQueue';
import GraphExplorer from './GraphExplorer';
import '../../styles/admin.css';

export default function AdminKnowledgePage() {
  const [activeTab, setActiveTab] = useState('proposals');

  return (
    <div className="admin-knowledge page-enter">
      <div className="admin-toolbar">
        <div className="admin-tabs">
          {['proposals', 'explorer'].map(tab => (
            <button
              key={tab}
              className={`admin-tab ${activeTab === tab ? 'active' : ''}`}
              onClick={() => setActiveTab(tab)}
            >
              {tab.charAt(0).toUpperCase() + tab.slice(1)}
            </button>
          ))}
        </div>
      </div>
      {activeTab === 'proposals' && <ProposalReviewQueue />}
      {activeTab === 'explorer' && <GraphExplorer />}
    </div>
  );
}
