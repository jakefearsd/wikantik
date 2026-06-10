import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from '../hooks/useAuth';
import ChangePasswordPage from './ChangePasswordPage';

// ---------------------------------------------------------------------------
// Mock api/client so AuthProvider.useEffect does not fire real HTTP calls.
// ---------------------------------------------------------------------------
vi.mock('../api/client', () => ({
  api: {
    getUser: vi.fn().mockResolvedValue({ authenticated: true, username: 'bob', roles: ['Authenticated'] }),
    login: vi.fn(),
    logout: vi.fn(),
    updateProfile: vi.fn(),
  },
}));

beforeEach(() => {
  vi.clearAllMocks();
});

/**
 * Render ChangePasswordPage inside a MemoryRouter with the given initial URL.
 * AuthProvider wraps it so useAuth() does not throw.
 * A /wiki/Main stub route is included to capture successful-navigation assertions.
 */
function renderChangePasswordPage(initialEntry = '/change-password') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <AuthProvider>
        <Routes>
          <Route path="/change-password" element={<ChangePasswordPage />} />
          <Route path="/wiki/Main" element={<div data-testid="wiki-main">Wiki Main</div>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('ChangePasswordPage', () => {
  it('renders current, new, confirm password fields and submit', () => {
    renderChangePasswordPage();
    expect(screen.getByTestId('change-current')).toBeInTheDocument();
    expect(screen.getByTestId('change-new')).toBeInTheDocument();
    expect(screen.getByTestId('change-confirm')).toBeInTheDocument();
    expect(screen.getByTestId('change-submit')).toBeInTheDocument();
  });

  it('rejects mismatched new passwords client-side without calling api.updateProfile', async () => {
    const { api } = await import('../api/client');
    renderChangePasswordPage();

    fireEvent.change(screen.getByTestId('change-current'), { target: { value: 'oldpass' } });
    fireEvent.change(screen.getByTestId('change-new'), { target: { value: 'newpass1' } });
    fireEvent.change(screen.getByTestId('change-confirm'), { target: { value: 'newpass2' } });
    fireEvent.click(screen.getByTestId('change-submit'));

    await waitFor(() => {
      expect(screen.getByText('New passwords do not match')).toBeInTheDocument();
    });
    expect(api.updateProfile).not.toHaveBeenCalled();
  });

  it('submits { currentPassword, newPassword } to api.updateProfile and navigates to /wiki/Main on success', async () => {
    const { api } = await import('../api/client');
    api.updateProfile.mockResolvedValue({});
    renderChangePasswordPage();

    fireEvent.change(screen.getByTestId('change-current'), { target: { value: 'oldpass' } });
    fireEvent.change(screen.getByTestId('change-new'), { target: { value: 'newpass' } });
    fireEvent.change(screen.getByTestId('change-confirm'), { target: { value: 'newpass' } });
    fireEvent.click(screen.getByTestId('change-submit'));

    await waitFor(() => {
      expect(api.updateProfile).toHaveBeenCalledWith({ currentPassword: 'oldpass', newPassword: 'newpass' });
    });
    await waitFor(() => {
      expect(screen.getByTestId('wiki-main')).toBeInTheDocument();
    });
  });

  it('surfaces server validation errors from err.body.message', async () => {
    const { api } = await import('../api/client');
    const err = new Error('Current password is incorrect');
    err.body = { message: 'Current password is incorrect' };
    api.updateProfile.mockRejectedValue(err);
    renderChangePasswordPage();

    fireEvent.change(screen.getByTestId('change-current'), { target: { value: 'wrongpass' } });
    fireEvent.change(screen.getByTestId('change-new'), { target: { value: 'newpass' } });
    fireEvent.change(screen.getByTestId('change-confirm'), { target: { value: 'newpass' } });
    fireEvent.click(screen.getByTestId('change-submit'));

    await waitFor(() => {
      expect(screen.getByText('Current password is incorrect')).toBeInTheDocument();
    });
  });
});
