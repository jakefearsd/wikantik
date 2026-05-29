import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
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

  it('shows identity, mentions badge, and an owned page for authed users', () => {
    useAuth.mockReturnValue({ user: { authenticated: true, username: 'Alice', loginPrincipal: 'alice', roles: [] }, logout: () => {} });
    renderZone();
    expect(screen.getByText('Alice')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getByText('Foo')).toBeInTheDocument();
  });

  it('hides the Drafts section when there are no drafts', () => {
    useAuth.mockReturnValue({ user: { authenticated: true, username: 'Alice', loginPrincipal: 'alice', roles: [] }, logout: () => {} });
    renderZone();
    expect(screen.queryByText(/resume editing/i)).not.toBeInTheDocument();
  });
});
