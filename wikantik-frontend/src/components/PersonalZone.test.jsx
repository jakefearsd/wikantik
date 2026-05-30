import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import PersonalZone from './PersonalZone';

vi.mock('../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../hooks/useUnreadMentions', () => ({ useUnreadMentions: () => ({ count: 2 }) }));
vi.mock('../hooks/useMyPages', () => ({ useMyPages: () => ({ pages: [{ slug: 'Foo', title: 'Foo' }], loading: false }) }));
vi.mock('../hooks/useMyBlog', () => ({ useMyBlog: () => ({ entries: [], loading: false }) }));
vi.mock('../hooks/useRecentlyViewed', () => ({ useRecentlyViewed: () => ({ items: [], record: () => {} }) }));
vi.mock('../hooks/useDrafts', () => ({ useDrafts: () => ({ drafts: [], removeDraft: () => {} }) }));

import { useAuth } from '../hooks/useAuth';

beforeEach(() => { localStorage.clear(); vi.clearAllMocks(); });

const renderZone = () =>
  render(<MemoryRouter><PersonalZone onMobileClose={() => {}} onNewArticle={() => {}} /></MemoryRouter>);

describe('PersonalZone', () => {
  it('renders nothing for anonymous users', () => {
    useAuth.mockReturnValue({ user: { authenticated: false, username: 'anonymous' } });
    const { container } = renderZone();
    expect(container.firstChild).toBeNull();
  });

  it('shows identity always and surfaces the mentions badge on the collapsed Me header', () => {
    useAuth.mockReturnValue({ user: { authenticated: true, username: 'Alice', loginPrincipal: 'alice', roles: [] }, logout: () => {} });
    renderZone();
    // Identity + New Article stay visible above the collapse.
    expect(screen.getByText('Alice')).toBeInTheDocument();
    expect(screen.getByText('+ New Article')).toBeInTheDocument();
    // Unread-mentions badge is surfaced on the Me header even while collapsed.
    expect(screen.getByText('2')).toBeInTheDocument();
  });

  it('keeps the Me detail collapsed by default and reveals it on click', () => {
    useAuth.mockReturnValue({ user: { authenticated: true, username: 'Alice', loginPrincipal: 'alice', roles: [] }, logout: () => {} });
    renderZone();
    // Collapsed: owned page + sub-section headers are not in the DOM yet.
    expect(screen.queryByText('Foo')).not.toBeInTheDocument();
    expect(screen.queryByText('My pages')).not.toBeInTheDocument();
    // Open the Me section.
    fireEvent.click(screen.getByText('Me'));
    expect(screen.getByText('My pages')).toBeInTheDocument();
    expect(screen.getByText('Foo')).toBeInTheDocument();
  });

  it('hides the Drafts section when there are no drafts', () => {
    useAuth.mockReturnValue({ user: { authenticated: true, username: 'Alice', loginPrincipal: 'alice', roles: [] }, logout: () => {} });
    renderZone();
    fireEvent.click(screen.getByText('Me'));
    expect(screen.queryByText(/resume editing/i)).not.toBeInTheDocument();
  });
});
