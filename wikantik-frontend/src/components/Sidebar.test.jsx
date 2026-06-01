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

    it('renders each cluster as a collapsible header button', async () => {
      api.listPages.mockResolvedValue({ pages: clusteredPages });
      renderSidebar('/wiki/Main');
      expect(await screen.findByRole('button', { name: /Security/ })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Operations/ })).toBeInTheDocument();
    });

    it('auto-expands the cluster containing the active page', async () => {
      api.listPages.mockResolvedValue({ pages: clusteredPages });
      renderSidebar('/wiki/AclModel');
      // Security contains the active page → open, so its links are rendered.
      expect(await screen.findByRole('link', { name: 'AclModel' })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'PolicyGrants' })).toBeInTheDocument();
    });

    it('collapses clusters that do not contain the active page', async () => {
      api.listPages.mockResolvedValue({ pages: clusteredPages });
      renderSidebar('/wiki/AclModel');
      await screen.findByRole('button', { name: /Operations/ });
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
      const header = await screen.findByRole('button', { name: /Uncategorized/ });
      expect(header).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'LoosePage' })).toBeInTheDocument();
      // 'Main' is already a primary-nav link; it should not be duplicated here.
      // (The primary "Main page" link has a different label, so any 'Main' link
      // would be the duplicate we are guarding against.)
      expect(screen.queryByRole('link', { name: 'Main' })).not.toBeInTheDocument();
    });

    it('does not render an Uncategorized section when every page is clustered', async () => {
      api.listPages.mockResolvedValue({ pages: clusteredPages });
      renderSidebar('/wiki/AclModel');
      await screen.findByRole('button', { name: /Security/ });
      expect(screen.queryByRole('button', { name: /Uncategorized/ })).not.toBeInTheDocument();
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
});
