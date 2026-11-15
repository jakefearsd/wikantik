import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../api/work', () => ({ getMe: vi.fn() }));
vi.mock('../../hooks/useAuth', () => ({ useAuth: vi.fn() }));
import { getMe } from '../../api/work';
import { useAuth } from '../../hooks/useAuth';
import WorkHome from './WorkHome';

describe('WorkHome', () => {
  it('lists projects and what waits on the viewer', async () => {
    useAuth.mockReturnValue({ user: { authenticated: true, roles: [] } });
    getMe.mockResolvedValue({
      projects: [{ id: 1, key: 'PLAT', name: 'Platform', role: 'member' }],
      waitingOn: [{ id: 5, key: 'PLAT-5', projectId: 1, title: 'Review me' }],
    });
    render(<MemoryRouter><WorkHome /></MemoryRouter>);
    expect(await screen.findByRole('link', { name: 'PLAT Platform' })).toHaveAttribute('href', '/work/PLAT');
    expect(screen.getByText(/Waiting on you \(1\)/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'PLAT-5' })).toHaveAttribute('href', '/work/item/PLAT-5');
    expect(screen.queryByRole('link', { name: 'Products' })).not.toBeInTheDocument();
  });

  it('offers Products to admins and an empty message without projects', async () => {
    useAuth.mockReturnValue({ user: { authenticated: true, roles: ['Admin'] } });
    getMe.mockResolvedValue({ projects: [], waitingOn: [] });
    render(<MemoryRouter><WorkHome /></MemoryRouter>);
    expect(await screen.findByText(/not a member of any project/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Products' })).toHaveAttribute('href', '/work/products');
    expect(screen.queryByTestId('waiting-lane')).not.toBeInTheDocument();
  });
});
