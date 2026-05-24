import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

// vi.mock is hoisted — the factory must be self-contained (no top-level
// variable refs). Use vi.fn() inline; grab the spy refs via import after.
vi.mock('../api/client', () => ({
  api: {
    getUser: vi.fn(),
    getProfile: vi.fn(),
    updateProfile: vi.fn(),
    deleteAccount: vi.fn(),
    logout: vi.fn(),
  },
}));

const mockLogout = vi.fn();
vi.mock('../hooks/useAuth', () => ({
  useAuth: vi.fn(),
}));

// Import after mocks are registered.
import UserPreferencesPage from './UserPreferencesPage';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';

const FAKE_USER = { authenticated: true, username: 'testuser', roles: ['Authenticated'] };
const FAKE_PROFILE = {
  loginName: 'testuser',
  wikiName: 'TestUser',
  fullName: 'Test User',
  email: 'test@example.com',
  bio: '',
};

beforeEach(() => {
  vi.clearAllMocks();
  api.getUser.mockResolvedValue(FAKE_USER);
  api.getProfile.mockResolvedValue(FAKE_PROFILE);
  api.updateProfile.mockResolvedValue(FAKE_PROFILE);
  api.deleteAccount.mockResolvedValue(null);
  api.logout.mockResolvedValue(null);
  mockLogout.mockResolvedValue(undefined);
  useAuth.mockReturnValue({ user: FAKE_USER, logout: mockLogout });
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/preferences']}>
      <UserPreferencesPage />
    </MemoryRouter>,
  );
}

describe('UserPreferencesPage — delete account section', () => {
  it('renders the delete-account button after profile loads', async () => {
    renderPage();
    await waitFor(() =>
      expect(screen.getByTestId('delete-account-button')).toBeInTheDocument(),
    );
  });

  it('clicking delete-account-button reveals the confirm input and confirm button', async () => {
    renderPage();
    const trigger = await screen.findByTestId('delete-account-button');
    expect(screen.queryByTestId('delete-confirm-input')).not.toBeInTheDocument();
    expect(screen.queryByTestId('delete-confirm-button')).not.toBeInTheDocument();

    fireEvent.click(trigger);

    expect(screen.getByTestId('delete-confirm-input')).toBeInTheDocument();
    expect(screen.getByTestId('delete-confirm-button')).toBeInTheDocument();
  });

  it('confirm button is disabled until the typed value matches the login name', async () => {
    renderPage();
    const trigger = await screen.findByTestId('delete-account-button');
    fireEvent.click(trigger);

    const input = screen.getByTestId('delete-confirm-input');
    const confirmBtn = screen.getByTestId('delete-confirm-button');

    // Initially disabled — nothing typed yet
    expect(confirmBtn).toBeDisabled();

    // Wrong value — still disabled
    fireEvent.change(input, { target: { value: 'wrong' } });
    expect(confirmBtn).toBeDisabled();

    // Exact login name match — enabled
    fireEvent.change(input, { target: { value: 'testuser' } });
    expect(confirmBtn).not.toBeDisabled();
  });

  it('clicking the confirm button calls api.deleteAccount with the login name', async () => {
    renderPage();
    const trigger = await screen.findByTestId('delete-account-button');
    fireEvent.click(trigger);

    const input = screen.getByTestId('delete-confirm-input');
    fireEvent.change(input, { target: { value: 'testuser' } });

    const confirmBtn = screen.getByTestId('delete-confirm-button');
    fireEvent.click(confirmBtn);

    await waitFor(() =>
      expect(api.deleteAccount).toHaveBeenCalledWith('testuser'),
    );
  });
});
