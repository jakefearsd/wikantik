import React from 'react';
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
import DiffViewer from './components/DiffViewer';
import './styles/globals.css';

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <AuthProvider>
      <BrowserRouter basename="/app">
        <Routes>
          <Route element={<App />}>
            <Route path="/" element={<Navigate to="/wiki/Main" replace />} />
            <Route path="/wiki/:name" element={<PageView />} />
            <Route path="/wiki" element={<Navigate to="/wiki/Main" replace />} />
            <Route path="/edit/:name" element={<PageEditor />} />
            <Route path="/diff/:name" element={<DiffViewer />} />
            <Route path="/search" element={<SearchResultsPage />} />
            <Route path="/admin" element={<AdminLayout />}>
              <Route index element={<Navigate to="users" replace />} />
              <Route path="users" element={<AdminUsersPage />} />
              <Route path="content" element={<AdminContentPage />} />
              <Route path="security" element={<AdminSecurityPage />} />
            </Route>
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  </React.StrictMode>
);
