import { useState, useEffect, createContext, useContext } from 'react';
import { api } from '../api/client';

const CapabilitiesContext = createContext(null);

// Fail-open defaults: every capability defaults to enabled (and genaiMode to
// "full") so a broken/slow /api/capabilities call never blanks out the UI of
// an existing deployment — the SPA renders exactly as it did before this
// endpoint existed until proven otherwise by a real `false` in the response.
export const DEFAULT_CAPABILITIES = {
  knowledgeGraph: true,
  hybridSearch: true,
  genaiMode: 'full',
  ontology: true,
  connectors: true,
  citations: true,
};

export function CapabilitiesProvider({ children }) {
  const [capabilities, setCapabilities] = useState(DEFAULT_CAPABILITIES);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    api.getCapabilities()
      .then((data) => {
        if (!cancelled && data) setCapabilities({ ...DEFAULT_CAPABILITIES, ...data });
      })
      .catch((e) => {
        // Fail open — keep the all-enabled defaults already in state.
        console.warn('Failed to load /api/capabilities; defaulting to all-enabled', e);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, []);

  return (
    <CapabilitiesContext.Provider value={{ capabilities, loading }}>
      {children}
    </CapabilitiesContext.Provider>
  );
}

// Deliberately does NOT throw when used outside a CapabilitiesProvider
// (unlike useAuth) — capability gating must never be the reason a component
// crashes, and plenty of existing unit tests render leaf components (Sidebar,
// AdminSidebar, KnowledgeGraphView, ...) without wrapping every provider in
// the tree. Falls back to the same fail-open defaults.
export function useCapabilities() {
  const ctx = useContext(CapabilitiesContext);
  return ctx || { capabilities: DEFAULT_CAPABILITIES, loading: false };
}
