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
vi.mock('../hooks/useDarkMode', () => ({ useDarkMode: () => [false, () => {}] }));
vi.mock('./PersonalZone', () => ({ default: () => null }));
vi.mock('./UserBadge', () => ({ default: () => null }));
vi.mock('./SearchOverlay', () => ({ default: () => null }));
vi.mock('./NewArticleModal', () => ({ default: () => null }));

import Sidebar from './Sidebar';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';

const renderSidebar = (initialEntry = '/') =>
  render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/wiki/:name" element={<Sidebar collapsed={false} onToggle={() => {}} />} />
        <Route path="*" element={<Sidebar collapsed={false} onToggle={() => {}} />} />
      </Routes>
    </MemoryRouter>
  );

const makeChanges = (n) => Array.from({ length: n }, (_, i) => ({ name: `Page${i + 1}` }));

beforeEach(() => {
  vi.clearAllMocks();
  useAuth.mockReturnValue({ user: { authenticated: false, roles: [] } });
  api.listPages.mockResolvedValue({ pages: [] });
  api.getRecentChanges.mockResolvedValue({ changes: [] });
});

describe('Sidebar', () => {
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
