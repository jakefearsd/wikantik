import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './hooks/useAuth';
import App from './App';
import PageView from './components/PageView';
import PageEditor from './components/PageEditor';
import './styles/globals.css';

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route element={<App />}>
            <Route path="/" element={<Navigate to="/wiki/Main" replace />} />
            <Route path="/wiki/:name" element={<PageView />} />
            <Route path="/wiki" element={<Navigate to="/wiki/Main" replace />} />
            <Route path="/edit/:name" element={<PageEditor />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  </React.StrictMode>
);
