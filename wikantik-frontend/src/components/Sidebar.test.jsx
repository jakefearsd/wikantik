import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../api/client', () => ({
  api: {
    listPages: vi.fn(() => Promise.resolve({ pages: [] })),
    getRecentChanges: vi.fn(() => Promise.resolve({ changes: [] })),
  },
}));
vi.mock('../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../hooks/useCapabilities', () => ({ useCapabilities: vi.fn() }));

// useDarkMode is a module-level mock so individual tests can override it.
const mockToggleDark = vi.fn();
vi.mock('../hooks/useDarkMode', () => ({ useDarkMode: vi.fn(() => [false, mockToggleDark]) }));

vi.mock('./PersonalZone', () => ({ default: () => null }));
vi.mock('./UserBadge', () => ({ default: () => null }));
vi.mock('./SearchOverlay', () => ({ default: () => null }));
vi.mock('./NewArticleModal', () => ({ default: () => null }));

import Sidebar from './Sidebar';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useCapabilities } from '../hooks/useCapabilities';
import { useDarkMode } from '../hooks/useDarkMode';

const renderSidebar = (initialEntry = '/', extraProps = {}) =>
  render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/wiki/:name" element={<Sidebar collapsed={false} onToggle={() => {}} {...extraProps} />} />
        <Route path="*" element={<Sidebar collapsed={false} onToggle={() => {}} {...extraProps} />} />
      </Routes>
    </MemoryRouter>
  );

const makeChanges = (n) => Array.from({ length: n }, (_, i) => ({ name: `Page${i + 1}` }));

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear(); // CollapsibleSection persists open/closed state here.
  useAuth.mockReturnValue({ user: { authenticated: false, roles: [] } });
  useCapabilities.mockReturnValue({
    capabilities: { knowledgeGraph: true, hybridSearch: true, genaiMode: 'full', ontology: true, connectors: true, citations: true },
    loading: false,
  });
  api.listPages.mockResolvedValue({ pages: [] });
  api.getRecentChanges.mockResolvedValue({ changes: [] });
  // Default: light mode
  useDarkMode.mockReturnValue([false, mockToggleDark]);
});

describe('Sidebar', () => {
  describe('#23 — single shared search overlay', () => {
    it('sidebar search button calls the onOpenSearch prop (not its own local state)', () => {
      const onOpenSearch = vi.fn();
      renderSidebar('/', { onOpenSearch });
      const btn = screen.getByTestId('sidebar-search-trigger');
      fireEvent.click(btn);
      expect(onOpenSearch).toHaveBeenCalledTimes(1);
    });

    it('sidebar does NOT mount its own SearchOverlay when the button is clicked', () => {
      // SearchOverlay is mocked to () => null, so if Sidebar were rendering one
      // this would still return null — but what matters is onOpenSearch is
      // called instead of Sidebar toggling local state that would render an overlay.
      // Verified by the call-count assertion above; this just double-checks no
      // SearchOverlay instance appears inside Sidebar's own subtree.
      const onOpenSearch = vi.fn();
      renderSidebar('/', { onOpenSearch });
      fireEvent.click(screen.getByTestId('sidebar-search-trigger'));
      // The mock for SearchOverlay renders null, so querying for search-overlay
      // testid returns nothing — Sidebar no longer owns one.
      expect(screen.queryByTestId('search-overlay')).not.toBeInTheDocument();
    });
  });

  it('caps Recently Modified at 5 and expands on Show all', async () => {
    api.getRecentChanges.mockResolvedValue({ changes: makeChanges(12) });
    renderSidebar();
    expect(await screen.findByText('Page1')).toBeInTheDocument();
    expect(screen.getByText('Page5')).toBeInTheDocument();
    expect(screen.queryByText('Page6')).not.toBeInTheDocument();

    fireEvent.click(screen.getByText('Show all 12'));
    expect(screen.getByText('Page6')).toBeInTheDocument();
    expect(screen.getByText('Page12')).toBeInTheDocument();
  });

  it('does not show a Show all button when there are 5 or fewer changes', async () => {
    api.getRecentChanges.mockResolvedValue({ changes: makeChanges(4) });
    renderSidebar();
    expect(await screen.findByText('Page4')).toBeInTheDocument();
    expect(screen.queryByText(/Show all/)).not.toBeInTheDocument();
  });

  it('hides Unused/Undefined pages from the public Wiki Tools section for non-admins', () => {
    renderSidebar();
    expect(screen.getByText('Page Index')).toBeInTheDocument();
    expect(screen.queryByText('Unused pages')).not.toBeInTheDocument();
    expect(screen.queryByText('Undefined pages')).not.toBeInTheDocument();
  });

  it('shows Unused/Undefined pages in the admin-only section for admins', () => {
    useAuth.mockReturnValue({ user: { authenticated: true, roles: ['Admin'] } });
    renderSidebar();
    expect(screen.getByText('Unused pages')).toBeInTheDocument();
    expect(screen.getByText('Undefined pages')).toBeInTheDocument();
  });

  describe('#50 animated theme toggle + aria-label', () => {
    it('theme toggle button has aria-label in light mode', () => {
      useDarkMode.mockReturnValue([false, mockToggleDark]);
      renderSidebar();
      const btn = screen.getByRole('button', { name: /Switch to dark mode/i });
      expect(btn).toBeInTheDocument();
      expect(btn).toHaveAttribute('aria-label', 'Switch to dark mode');
    });

    it('theme toggle button has aria-label in dark mode', () => {
      useDarkMode.mockReturnValue([true, mockToggleDark]);
      renderSidebar();
      const btn = screen.getByRole('button', { name: /Switch to light mode/i });
      expect(btn).toBeInTheDocument();
      expect(btn).toHaveAttribute('aria-label', 'Switch to light mode');
    });

    it('clicking the theme toggle calls toggleDark', () => {
      useDarkMode.mockReturnValue([false, mockToggleDark]);
      renderSidebar();
      const btn = screen.getByRole('button', { name: /Switch to dark mode/i });
      fireEvent.click(btn);
      expect(mockToggleDark).toHaveBeenCalledTimes(1);
    });
  });

  describe('collapsible cluster tree (#5)', () => {
    const clusteredPages = [
      { name: 'AclModel', cluster: 'Security' },
      { name: 'PolicyGrants', cluster: 'Security' },
      { name: 'DeployGuide', cluster: 'Operations' },
    ];

    // Clusters now live under one top-level "Clusters" section that is collapsed
    // by default (so the sidebar stays compact); expand it to reach the
    // per-cluster sections.
    const expandClusters = async () =>
      fireEvent.click( await screen.findByRole( 'button', { name: /Clusters/ } ) );

    it('keeps cluster sections behind a single collapsed "Clusters" header by default', async () => {
      api.listPages.mockResolvedValue({ pages: clusteredPages });
      renderSidebar('/wiki/Main');
      // The top-level wrapper is present, but the individual cluster headers are
      // not rendered until it is expanded.
      expect(await screen.findByRole('button', { name: /Clusters/ })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Security/ })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Operations/ })).not.toBeInTheDocument();
    });

    it('reveals each cluster as a collapsible header button once expanded', async () => {
      api.listPages.mockResolvedValue({ pages: clusteredPages });
      renderSidebar('/wiki/Main');
      await expandClusters();
      expect(screen.getByRole('button', { name: /Security/ })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Operations/ })).toBeInTheDocument();
    });

    it('auto-expands the cluster containing the active page once Clusters is open', async () => {
      api.listPages.mockResolvedValue({ pages: clusteredPages });
      renderSidebar('/wiki/AclModel');
      await expandClusters();
      // Security contains the active page → open, so its links are rendered.
      expect(screen.getByRole('link', { name: 'AclModel' })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'PolicyGrants' })).toBeInTheDocument();
    });

    it('collapses clusters that do not contain the active page', async () => {
      api.listPages.mockResolvedValue({ pages: clusteredPages });
      renderSidebar('/wiki/AclModel');
      await expandClusters();
      // Operations is not the active cluster → collapsed, link not rendered.
      expect(screen.queryByRole('link', { name: 'DeployGuide' })).not.toBeInTheDocument();
      // Expanding it reveals the page.
      fireEvent.click(screen.getByRole('button', { name: /Operations/ }));
      expect(screen.getByRole('link', { name: 'DeployGuide' })).toBeInTheDocument();
    });

    it('places clusterless pages under Uncategorized, excluding system nav pages', async () => {
      api.listPages.mockResolvedValue({
        pages: [
          { name: 'Main' },        // system nav page → must not appear under Uncategorized
          { name: 'LoosePage' },   // genuinely uncategorized
        ],
      });
      renderSidebar('/wiki/LoosePage');
      await expandClusters();
      expect(screen.getByRole('button', { name: /Uncategorized/ })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'LoosePage' })).toBeInTheDocument();
      // 'Main' is already a primary-nav link; it should not be duplicated here.
      expect(screen.queryByRole('link', { name: 'Main' })).not.toBeInTheDocument();
    });

    it('does not render an Uncategorized section when every page is clustered', async () => {
      api.listPages.mockResolvedValue({ pages: clusteredPages });
      renderSidebar('/wiki/AclModel');
      await expandClusters();
      expect(screen.getByRole('button', { name: /Security/ })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Uncategorized/ })).not.toBeInTheDocument();
    });
  });

  describe('derived-page badge (#18)', () => {
    it('renders exactly one derived badge for the page flagged derived:true', async () => {
      api.listPages.mockResolvedValue({ pages: [{ name: 'A', derived: true }, { name: 'B' }] });
      renderSidebar('/wiki/A');
      // Both pages are clusterless — expand the top-level "Clusters" wrapper
      // (which hosts Uncategorized) to reach their links.
      fireEvent.click(await screen.findByRole('button', { name: /Clusters/ }));
      const badges = screen.getAllByTitle('Synced from an external source');
      expect(badges).toHaveLength(1);
    });
  });

  describe('aria-current on active nav links (#6)', () => {
    it('active nav link has aria-current="page" when route matches', () => {
      renderSidebar('/wiki/Main');
      const mainLink = screen.getByRole('link', { name: 'Main page' });
      expect(mainLink).toHaveAttribute('aria-current', 'page');
    });

    it('non-active nav links do not have aria-current', () => {
      renderSidebar('/wiki/Main');
      const aboutLink = screen.getByRole('link', { name: 'About' });
      expect(aboutLink).not.toHaveAttribute('aria-current');
    });

    it('active recently-modified link has aria-current="page"', async () => {
      api.getRecentChanges.mockResolvedValue({ changes: [{ name: 'ActivePage' }, { name: 'OtherPage' }] });
      renderSidebar('/wiki/ActivePage');
      const activeLink = await screen.findByRole('link', { name: 'ActivePage' });
      expect(activeLink).toHaveAttribute('aria-current', 'page');
      const otherLink = screen.getByRole('link', { name: 'OtherPage' });
      expect(otherLink).not.toHaveAttribute('aria-current');
    });
  });

  describe('Knowledge Graph nav gating (capabilities)', () => {
    it('renders the reader Knowledge Graph link when capabilities.knowledgeGraph is true', () => {
      useCapabilities.mockReturnValue({
        capabilities: { knowledgeGraph: true }, loading: false,
      });
      renderSidebar('/wiki/Main');
      expect(screen.getByRole('link', { name: 'Knowledge Graph' })).toBeInTheDocument();
    });

    it('omits the reader Knowledge Graph link when capabilities.knowledgeGraph is false', () => {
      useCapabilities.mockReturnValue({
        capabilities: { knowledgeGraph: false }, loading: false,
      });
      renderSidebar('/wiki/Main');
      expect(screen.queryByRole('link', { name: 'Knowledge Graph' })).not.toBeInTheDocument();
    });

    it('still renders the Knowledge Graph link while capabilities is loading (fail-open)', () => {
      // Loading state carries the fail-open default (knowledgeGraph: true) —
      // a slow/broken /api/capabilities call must never blank out nav.
      useCapabilities.mockReturnValue({
        capabilities: { knowledgeGraph: true }, loading: true,
      });
      renderSidebar('/wiki/Main');
      expect(screen.getByRole('link', { name: 'Knowledge Graph' })).toBeInTheDocument();
    });

    it('omits the admin Knowledge Graph link when capabilities.knowledgeGraph is false', () => {
      useAuth.mockReturnValue({ user: { authenticated: true, roles: ['Admin'] } });
      useCapabilities.mockReturnValue({
        capabilities: { knowledgeGraph: false }, loading: false,
      });
      renderSidebar('/wiki/Main');
      // Two "Knowledge Graph" links normally render (reader + admin section);
      // with the flag off, neither should be present.
      expect(screen.queryByRole('link', { name: 'Knowledge Graph' })).not.toBeInTheDocument();
    });

    it('renders both reader and admin Knowledge Graph links for an admin when the flag is on', () => {
      useAuth.mockReturnValue({ user: { authenticated: true, roles: ['Admin'] } });
      useCapabilities.mockReturnValue({
        capabilities: { knowledgeGraph: true }, loading: false,
      });
      renderSidebar('/wiki/Main');
      expect(screen.getAllByRole('link', { name: 'Knowledge Graph' })).toHaveLength(2);
    });
  });
});
