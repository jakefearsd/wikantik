import React, { Suspense } from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './hooks/useAuth';
import { CapabilitiesProvider } from './hooks/useCapabilities';
import App from './App';
import PageView from './components/PageView';
import './styles/globals.css';

// Eager: hot-path reader views are kept in the main chunk so the first
// /wiki/{slug} navigation has no extra round trip. Everything else is
// code-split — admin pages, editors, blog, graph viewers — so anonymous
// readers don't pay for them.
const PageEditor = React.lazy(() => import('./components/PageEditor'));
const SearchResultsPage = React.lazy(() => import('./components/SearchResultsPage'));
const DiffViewer = React.lazy(() => import('./components/DiffViewer'));
const UserPreferencesPage = React.lazy(() => import('./components/UserPreferencesPage'));
const MentionsPage = React.lazy(() => import('./components/MentionsPage'));
const ResetPasswordPage = React.lazy(() => import('./components/ResetPasswordPage'));
const BlogDiscovery = React.lazy(() => import('./components/BlogDiscovery'));
const BlogHome = React.lazy(() => import('./components/BlogHome'));
const BlogEntry = React.lazy(() => import('./components/BlogEntry'));
const CreateBlog = React.lazy(() => import('./components/CreateBlog'));
const NewBlogEntry = React.lazy(() => import('./components/NewBlogEntry'));
const BlogEditor = React.lazy(() => import('./components/BlogEditor'));

const AdminLayout = React.lazy(() => import('./components/admin/AdminLayout'));
const OverviewDashboard = React.lazy(() => import('./components/admin/OverviewDashboard'));
const AdminUsersPage = React.lazy(() => import('./components/admin/AdminUsersPage'));
const AdminContentPage = React.lazy(() => import('./components/admin/AdminContentPage'));
const AdminSecurityPage = React.lazy(() => import('./components/admin/AdminSecurityPage'));
const AdminKnowledgePage = React.lazy(() => import('./components/admin/AdminKnowledgePage'));
const AdminApiKeysPage = React.lazy(() => import('./components/admin/AdminApiKeysPage'));
const AdminRetrievalQualityPage = React.lazy(() => import('./components/admin/AdminRetrievalQualityPage'));
const AdminDriftPage = React.lazy(() => import('./components/admin/AdminDriftPage'));
const AdminKgPolicyPage = React.lazy(() => import('./components/admin/AdminKgPolicyPage'));
const AdminKgPolicyExplain = React.lazy(() => import('./components/admin/AdminKgPolicyExplain'));
const AdminKgPolicyPending = React.lazy(() => import('./components/admin/AdminKgPolicyPending'));
const AdminKgPolicyBootstrap = React.lazy(() => import('./components/admin/AdminKgPolicyBootstrap'));
const AdminPageOwnershipPage = React.lazy(() => import('./components/admin/AdminPageOwnershipPage'));
const AdminAuditPage = React.lazy(() => import('./components/admin/AdminAuditPage'));
const AdminConnectorsPage = React.lazy(() => import('./components/admin/AdminConnectorsPage'));
const ConnectorDetailPage = React.lazy(() => import('./components/admin/ConnectorDetailPage'));
const AddConnectorWizard = React.lazy(() => import('./components/admin/AddConnectorWizard'));

const LoginPage = React.lazy(() => import('./components/LoginPage'));
const ChangePasswordPage = React.lazy(() => import('./components/ChangePasswordPage'));
const PageGraphView = React.lazy(() => import('./components/pagegraph/PageGraphView.jsx'));
const KnowledgeGraphView = React.lazy(() => import('./components/kgraph/KnowledgeGraphView.jsx'));

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <CapabilitiesProvider>
    <AuthProvider>
      <BrowserRouter basename={(typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '/'}>
        <Suspense fallback={<div className="route-loading" aria-busy="true" />}>
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
            <Route path="/me/mentions" element={<MentionsPage />} />
            <Route path="/reset-password" element={<ResetPasswordPage />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/change-password" element={<ChangePasswordPage />} />
            <Route path="/admin" element={<AdminLayout />}>
              <Route index element={<OverviewDashboard />} />
              <Route path="users" element={<AdminUsersPage />} />
              <Route path="content" element={<AdminContentPage />} />
              <Route path="security" element={<AdminSecurityPage />} />
              <Route path="knowledge-graph" element={<AdminKnowledgePage />} />
              <Route path="apikeys" element={<AdminApiKeysPage />} />
              <Route path="retrieval-quality" element={<AdminRetrievalQualityPage />} />
              <Route path="drift" element={<AdminDriftPage />} />
              <Route path="kg-policy" element={<AdminKgPolicyPage />} />
              <Route path="kg-policy/explain" element={<AdminKgPolicyExplain />} />
              <Route path="kg-policy/pending" element={<AdminKgPolicyPending />} />
              <Route path="kg-policy/bootstrap" element={<AdminKgPolicyBootstrap />} />
              <Route path="page-ownership" element={<AdminPageOwnershipPage />} />
              <Route path="audit" element={<AdminAuditPage />} />
              <Route path="connectors" element={<AdminConnectorsPage />} />
              <Route path="connectors/new" element={<AddConnectorWizard />} />
              <Route path="connectors/:id" element={<ConnectorDetailPage />} />
            </Route>
            <Route path="/blog" element={<BlogDiscovery />} />
            <Route path="/blog/create" element={<CreateBlog />} />
            <Route path="/blog/:username/new" element={<NewBlogEntry />} />
            <Route path="/blog/:username/Blog" element={<BlogHome />} />
            <Route path="/blog/:username/:entryName" element={<BlogEntry />} />
          </Route>
        </Routes>
        </Suspense>
      </BrowserRouter>
    </AuthProvider>
    </CapabilitiesProvider>
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
