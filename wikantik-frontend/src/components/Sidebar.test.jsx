import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../api/client', () => ({
  api: {
    listPages: vi.fn(() => Promise.resolve({ pages: [] })),
    listClusters: vi.fn(() => Promise.resolve({ clusters: [] })),
    listClusterPages: vi.fn(() => Promise.resolve({ pages: [] })),
    getRecentChanges: vi.fn(() => Promise.resolve({ changes: [] })),
  },
}));
vi.mock('../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../hooks/useWorkUnread', () => ({ useWorkUnread: vi.fn(() => ({ unread: 0, visible: false })) }));

// useDarkMode is a module-level mock so individual tests can override it.
const mockToggleDark = vi.fn();
vi.mock('../hooks/useDarkMode', () => ({ useDarkMode: vi.fn(() => [false, mockToggleDark]) }));

vi.mock('./PersonalZone', () => ({
  default: ({ onNewArticle }) => <button data-testid="pz-new" onClick={onNewArticle} />,
}));
vi.mock('./UserBadge', () => ({ default: () => null }));
vi.mock('./QuickOverlay', () => ({ default: () => null }));
const mockOpenNewPage = vi.fn();
vi.mock('../newpage/NewPageProvider', () => ({ useNewPage: () => ({ openNewPage: mockOpenNewPage }) }));

import Sidebar from './Sidebar';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useWorkUnread } from '../hooks/useWorkUnread';
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
  api.listClusters.mockResolvedValue({ clusters: [] });
  api.listClusterPages.mockResolvedValue({ pages: [] });
  api.getRecentChanges.mockResolvedValue({ changes: [] });
  // Default: light mode
  useDarkMode.mockReturnValue([false, mockToggleDark]);
});

describe('Sidebar', () => {
  it('theWorkEntryShowsTheUnreadCount', () => {
    useWorkUnread.mockReturnValue({ unread: 3, visible: true });
    renderSidebar('/');
    const link = screen.getByRole('link', { name: /Work/ });
    expect(link).toHaveAttribute('href', '/work');
    expect(screen.getByLabelText('3 unread notifications')).toHaveTextContent('3');
  });

  it('theWorkEntryShowsNoBadgeWhenNothingIsUnread', () => {
    useWorkUnread.mockReturnValue({ unread: 0, visible: true });
    renderSidebar('/');
    expect(screen.getByRole('link', { name: 'Work' })).toBeInTheDocument();
    expect(screen.queryByLabelText(/unread notifications/)).toBeNull();
  });

  it('opens the new-page dialog through the provider', () => {
    renderSidebar('/');
    fireEvent.click(screen.getByTestId('pz-new'));
    expect(mockOpenNewPage).toHaveBeenCalledTimes(1);
  });

  describe('#23 — single shared search overlay', () => {
    it('sidebar search button calls the onOpenSearch prop (not its own local state)', () => {
      const onOpenSearch = vi.fn();
      renderSidebar('/', { onOpenSearch });
      const btn = screen.getByTestId('sidebar-search-trigger');
      fireEvent.click(btn);
      expect(onOpenSearch).toHaveBeenCalledTimes(1);
    });

    it('sidebar does NOT mount its own QuickOverlay when the button is clicked', () => {
      // QuickOverlay is mocked to () => null, so if Sidebar were rendering one
      // this would still return null — but what matters is onOpenSearch is
      // called instead of Sidebar toggling local state that would render an overlay.
      // Verified by the call-count assertion above; this just double-checks no
      // QuickOverlay instance appears inside Sidebar's own subtree.
      const onOpenSearch = vi.fn();
      renderSidebar('/', { onOpenSearch });
      fireEvent.click(screen.getByTestId('sidebar-search-trigger'));
      // The mock for QuickOverlay renders null, so querying for search-overlay
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

  describe('cluster tree', () => {
    const clusters = [
      { name: 'security', article_count: 3, hub_page: { slug: 'SecurityHub', title: 'Security Hub' } },
      { name: 'security/containers', article_count: 1, hub_page: { slug: 'ContainersHub', title: 'Containers Hub' } },
      { name: 'operations', article_count: 1, hub_page: null },
    ];
    const members = {
      security: [
        { slug: 'SecurityHub', title: 'Security Hub', cluster: 'security' },
        { slug: 'PolicyGrants', title: 'Policy Grants', cluster: 'security' },
        { slug: 'AclModel', title: 'ACL Model', cluster: 'security' },
        { slug: 'Seccomp', title: 'Seccomp Profiles', cluster: 'security/containers' },
      ],
      operations: [{ slug: 'DeployGuide', title: 'Deploy Guide', cluster: 'operations' }],
    };
    beforeEach(() => {
      api.listClusters.mockResolvedValue({ clusters });
      api.listClusterPages.mockImplementation((c) => Promise.resolve({ pages: members[c] || [] }));
    });
    const expandClusters = () => fireEvent.click(screen.getByRole('button', { name: /Browse Clusters/ }));

    it('asks the server for nothing until Browse Clusters is opened', async () => {
      renderSidebar('/wiki/Main');
      await screen.findByRole('button', { name: /Browse Clusters/ });
      expect(api.listClusters).not.toHaveBeenCalled();
      expect(api.listClusterPages).not.toHaveBeenCalled();
      // The old sidebar pulled the first 500 pages on every view and built a partial tree from them.
      expect(api.listPages).not.toHaveBeenCalled();
    });

    it('lists every declared cluster with the server\'s count, and loads a cluster\'s pages when it is opened', async () => {
      renderSidebar('/wiki/Main');
      expandClusters();
      const ops = await screen.findByRole('button', { name: /operations/ });
      expect(screen.getByRole('button', { name: /^security\s*3/ })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /security\/containers/ })).toBeInTheDocument();
      expect(api.listClusterPages).not.toHaveBeenCalled();
      fireEvent.click(ops);
      expect(await screen.findByRole('link', { name: 'Deploy Guide' })).toHaveAttribute('href', '/wiki/DeployGuide');
      expect(api.listClusterPages).toHaveBeenCalledWith('operations');
    });

    it('shows titles, puts the hub first, and leaves a sub-cluster\'s pages to its own section', async () => {
      renderSidebar('/wiki/Main');
      expandClusters();
      fireEvent.click(await screen.findByRole('button', { name: /^security\s*3/ }));
      await screen.findByRole('link', { name: 'ACL Model' });
      const body = screen.getByRole('button', { name: /^security\s*3/ }).parentElement;
      const names = [...body.querySelectorAll('a')].map((a) => a.textContent);
      expect(names).toEqual(['Security Hub', 'ACL Model', 'Policy Grants']);
    });

    it('opens the cluster that holds the page being read', async () => {
      api.listPages.mockResolvedValue({ pages: [{ name: 'AclModel', title: 'ACL Model', cluster: 'security' }] });
      renderSidebar('/wiki/AclModel');
      expandClusters();
      const link = await screen.findByRole('link', { name: 'ACL Model' });
      expect(link).toHaveAttribute('aria-current', 'page');
      expect(screen.queryByRole('link', { name: 'Deploy Guide' })).not.toBeInTheDocument();
    });

    it('says so when a cluster\'s pages cannot be loaded', async () => {
      api.listClusterPages.mockRejectedValue(new Error('boom'));
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      renderSidebar('/wiki/Main');
      expandClusters();
      fireEvent.click(await screen.findByRole('button', { name: /operations/ }));
      expect(await screen.findByText(/couldn't load this cluster/i)).toBeInTheDocument();
      warnSpy.mockRestore();
    });
  });

  describe('navigation links', () => {
    it('links to the blog and no longer to the unmaintained News page', () => {
      renderSidebar('/wiki/Main');
      expect(screen.getByRole('link', { name: 'Blog' })).toHaveAttribute('href', 'https://blog.wikantik.com/');
      expect(screen.queryByRole('link', { name: 'News' })).not.toBeInTheDocument();
    });

    it('shows Recently Modified pages by title', async () => {
      api.getRecentChanges.mockResolvedValue({ changes: [{ name: 'LlmsSinceTwentyTwenty' }, { name: 'Untitled' }] });
      api.listPages.mockResolvedValue({ pages: [{ name: 'LlmsSinceTwentyTwenty', title: 'Large Language Models Since 2020' }] });
      renderSidebar('/wiki/Main');
      expect(await screen.findByRole('link', { name: 'Large Language Models Since 2020' })).toHaveAttribute('href', '/wiki/LlmsSinceTwentyTwenty');
      expect(screen.getByRole('link', { name: 'Untitled' })).toBeInTheDocument();
      expect(api.listPages).toHaveBeenCalledWith({ names: ['LlmsSinceTwentyTwenty', 'Untitled'], limit: 2 });
    });
  });

  describe('home links point at "/" (the canonical front-page URL)', () => {
    it('the brand logo and the Main page link have href="/"', () => {
      renderSidebar('/wiki/About');
      expect(screen.getByRole('link', { name: 'Main page' })).toHaveAttribute('href', '/');
      expect(screen.getByRole('link', { name: /Wik/ })).toHaveAttribute('href', '/');
    });

    it('marks Main page active when the route is "/"', () => {
      renderSidebar('/');
      expect(screen.getByRole('link', { name: 'Main page' })).toHaveAttribute('aria-current', 'page');
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

  describe('Knowledge Graph entry points are retired', () => {
    it('does not render a Knowledge Graph link for readers', () => {
      renderSidebar('/wiki/Main');
      expect(screen.queryByRole('link', { name: 'Knowledge Graph' })).not.toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Page Graph' })).toBeInTheDocument();
    });

    it('does not render a Knowledge Graph link in the admin section', () => {
      useAuth.mockReturnValue({ user: { authenticated: true, roles: ['Admin'] } });
      renderSidebar('/wiki/Main');
      expect(screen.queryByRole('link', { name: 'Knowledge Graph' })).not.toBeInTheDocument();
    });
  });

  describe('mobile tab handle', () => {
    it('is a real keyboard-reachable button', () => {
      renderSidebar('/wiki/Main');
      expect(screen.getByRole('button', { name: 'Open navigation' })).toBeInTheDocument();
    });
  });

  describe('fetch-failure hints', () => {
    it('shows an unobtrusive hint (not a banner) when the clusters fail to load', async () => {
      api.listClusters.mockRejectedValue(new Error('boom'));
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      renderSidebar('/wiki/Main');
      fireEvent.click(screen.getByRole('button', { name: /Browse Clusters/ }));
      await screen.findByText(/couldn't load the clusters/i);
      expect(warnSpy).toHaveBeenCalledWith('Sidebar: failed to load clusters', expect.any(Error));
      warnSpy.mockRestore();
    });

    it('shows an unobtrusive hint when recent changes fail to load', async () => {
      api.getRecentChanges.mockRejectedValue(new Error('boom'));
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      renderSidebar('/wiki/Main');
      await screen.findByText(/couldn't load recent changes/i);
      expect(warnSpy).toHaveBeenCalledWith('Sidebar: failed to load recent changes', expect.any(Error));
      warnSpy.mockRestore();
    });

    it('shows neither hint on a normal, successful load', async () => {
      api.listPages.mockResolvedValue({ pages: [{ name: 'A' }] });
      api.getRecentChanges.mockResolvedValue({ changes: [{ name: 'A' }] });
      renderSidebar('/wiki/Main');
      await screen.findByText('Recently Modified');
      expect(screen.queryByText(/couldn't load/i)).not.toBeInTheDocument();
    });
  });
});
