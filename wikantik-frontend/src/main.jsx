import React, { Suspense } from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './hooks/useAuth';
import App from './App';
import PageView from './components/PageView';
import PageEditor from './components/PageEditor';
import SearchResultsPage from './components/SearchResultsPage';
import AdminLayout from './components/admin/AdminLayout';
import AdminUsersPage from './components/admin/AdminUsersPage';
import AdminContentPage from './components/admin/AdminContentPage';
import AdminSecurityPage from './components/admin/AdminSecurityPage';
import AdminKnowledgePage from './components/admin/AdminKnowledgePage';
import AdminApiKeysPage from './components/admin/AdminApiKeysPage';
import AdminRetrievalQualityPage from './components/admin/AdminRetrievalQualityPage';
import AdminKgPolicyPage from './components/admin/AdminKgPolicyPage';
import AdminKgPolicyExplain from './components/admin/AdminKgPolicyExplain';
import AdminKgPolicyPending from './components/admin/AdminKgPolicyPending';
import AdminKgPolicyBootstrap from './components/admin/AdminKgPolicyBootstrap';
import DiffViewer from './components/DiffViewer';
import UserPreferencesPage from './components/UserPreferencesPage';
import ResetPasswordPage from './components/ResetPasswordPage';
import BlogDiscovery from './components/BlogDiscovery';
import BlogHome from './components/BlogHome';
import BlogEntry from './components/BlogEntry';
import CreateBlog from './components/CreateBlog';
import NewBlogEntry from './components/NewBlogEntry';
import BlogEditor from './components/BlogEditor';
import './styles/globals.css';

const PageGraphView = React.lazy(() => import('./components/pagegraph/PageGraphView.jsx'));
const KnowledgeGraphView = React.lazy(() => import('./components/kgraph/KnowledgeGraphView.jsx'));

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <AuthProvider>
      <BrowserRouter basename={(typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '/'}>
        <Routes>
          <Route element={<App />}>
            <Route path="/" element={<Navigate to="/wiki/Main" replace />} />
            <Route path="/wiki/:name" element={<PageView />} />
            <Route path="/wiki" element={<Navigate to="/wiki/Main" replace />} />
            <Route path="/edit/blog/:username/:pageName" element={<BlogEditor />} />
            <Route path="/edit/:name" element={<PageEditor />} />
            <Route path="/diff/:name" element={<DiffViewer />} />
            <Route path="/search" element={<SearchResultsPage />} />
            <Route path="/page-graph" element={
              <Suspense fallback={<div className="graph-loading"><p>Loading page graph...</p></div>}>
                <PageGraphView />
              </Suspense>
            } />
            <Route path="/knowledge-graph" element={
              <Suspense fallback={<div className="graph-loading"><p>Loading knowledge graph...</p></div>}>
                <KnowledgeGraphView />
              </Suspense>
            } />
            <Route path="/preferences" element={<UserPreferencesPage />} />
            <Route path="/reset-password" element={<ResetPasswordPage />} />
            <Route path="/admin" element={<AdminLayout />}>
              <Route index element={<Navigate to="users" replace />} />
              <Route path="users" element={<AdminUsersPage />} />
              <Route path="content" element={<AdminContentPage />} />
              <Route path="security" element={<AdminSecurityPage />} />
              <Route path="knowledge-graph" element={<AdminKnowledgePage />} />
              <Route path="apikeys" element={<AdminApiKeysPage />} />
              <Route path="retrieval-quality" element={<AdminRetrievalQualityPage />} />
              <Route path="kg-policy" element={<AdminKgPolicyPage />} />
              <Route path="kg-policy/explain" element={<AdminKgPolicyExplain />} />
              <Route path="kg-policy/pending" element={<AdminKgPolicyPending />} />
              <Route path="kg-policy/bootstrap" element={<AdminKgPolicyBootstrap />} />
            </Route>
            <Route path="/blog" element={<BlogDiscovery />} />
            <Route path="/blog/create" element={<CreateBlog />} />
            <Route path="/blog/:username/new" element={<NewBlogEntry />} />
            <Route path="/blog/:username/Blog" element={<BlogHome />} />
            <Route path="/blog/:username/:entryName" element={<BlogEntry />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  </React.StrictMode>
);

// App rendered successfully — clear the stale-asset reload flag so that
// the next deploy can trigger a fresh reload if needed.
sessionStorage.removeItem('__wikantik_reload');

// Remove cache-bust param from URL bar so users see clean URLs
if (window.location.search.includes('_cb=')) {
  const url = new URL(window.location);
  url.searchParams.delete('_cb');
  window.history.replaceState(null, '', url.pathname + url.search + url.hash);
}
