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
    self: {
      listApiKeys: vi.fn().mockResolvedValue({ keys: [] }),
      createApiKey: vi.fn(),
      rotateApiKey: vi.fn(),
      revokeApiKey: vi.fn(),
    },
  },
}));

const mockLogout = vi.fn();
const mockRefresh = vi.fn();
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
  mockRefresh.mockResolvedValue(undefined);
  useAuth.mockReturnValue({ user: FAKE_USER, logout: mockLogout, refresh: mockRefresh });
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
    expect(await screen.findByTestId('delete-account-button')).toBeInTheDocument();
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

  it('renders the API Keys section', async () => {
    renderPage();
    expect(await screen.findByText('API Keys')).toBeInTheDocument();
    expect(await screen.findByText('You have no active API keys.')).toBeInTheDocument();
  });
});

import { Routes, Route } from 'react-router-dom';

function getPasswordInputs() {
  const [current, newPw, confirmPw] = document.querySelectorAll('input[type="password"]');
  return { current, newPw, confirmPw };
}

function renderWithRoutes(initialPath = '/preferences') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/preferences" element={<UserPreferencesPage />} />
        <Route path="/wiki/Main" element={<div>MAIN PAGE MARKER</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('UserPreferencesPage — auth gating', () => {
  it('renders nothing when there is no user', () => {
    useAuth.mockReturnValue({ user: null, logout: mockLogout });
    const { container } = renderPage();
    expect(container.textContent).toBe('');
  });

  it('redirects to /wiki/Main when the user is present but not authenticated', async () => {
    useAuth.mockReturnValue({ user: { authenticated: false }, logout: mockLogout });
    renderWithRoutes();
    expect(await screen.findByText('MAIN PAGE MARKER')).toBeInTheDocument();
  });
});

describe('UserPreferencesPage — profile load', () => {
  it('shows a loading indicator before the profile resolves', async () => {
    let resolveProfile;
    api.getProfile.mockReturnValue(new Promise((res) => { resolveProfile = res; }));
    renderPage();
    expect(screen.getByText('Loading profile...')).toBeInTheDocument();
    resolveProfile(FAKE_PROFILE);
    expect(await screen.findByText('Preferences')).toBeInTheDocument();
  });

  it('shows the error banner when loading the profile fails', async () => {
    api.getProfile.mockRejectedValue(new Error('network down'));
    renderPage();
    expect(await screen.findByText('network down')).toBeInTheDocument();
  });

  it('shows a generic error when the load failure has no message', async () => {
    api.getProfile.mockRejectedValue(new Error());
    renderPage();
    expect(await screen.findByText('Failed to load profile')).toBeInTheDocument();
  });

  it('pre-fills the form fields from the loaded profile', async () => {
    renderPage();
    expect(await screen.findByDisplayValue('Test User')).toBeInTheDocument();
    expect(screen.getByDisplayValue('test@example.com')).toBeInTheDocument();
    expect(screen.getByDisplayValue('testuser')).toBeInTheDocument(); // login name (read-only)
    expect(screen.getByDisplayValue('TestUser')).toBeInTheDocument(); // wiki name (read-only)
  });

  it('defaults fullName/email/bio to empty strings when absent from the profile', async () => {
    api.getProfile.mockResolvedValue({ loginName: 'bareuser' });
    renderPage();
    await screen.findByText('Preferences');
    const fullNameInput = screen.getByDisplayValue('bareuser'); // login name readonly still shows
    expect(fullNameInput).toBeInTheDocument();
    expect(screen.getByPlaceholderText('Tell others about yourself...')).toHaveValue('');
  });
});

describe('UserPreferencesPage — profile form submission', () => {
  it('editing fields updates their values', async () => {
    renderPage();
    const fullNameInput = await screen.findByDisplayValue('Test User');
    fireEvent.change(fullNameInput, { target: { value: 'New Name' } });
    expect(fullNameInput).toHaveValue('New Name');

    const emailInput = screen.getByDisplayValue('test@example.com');
    fireEvent.change(emailInput, { target: { value: 'new@example.com' } });
    expect(emailInput).toHaveValue('new@example.com');

    const bioInput = screen.getByPlaceholderText('Tell others about yourself...');
    fireEvent.change(bioInput, { target: { value: 'Hello there' } });
    expect(bioInput).toHaveValue('Hello there');
    expect(screen.getByText('11 / 1000')).toBeInTheDocument();
  });

  it('the bio counter switches to the danger color past 950 characters', async () => {
    renderPage();
    const bioInput = await screen.findByPlaceholderText('Tell others about yourself...');
    fireEvent.change(bioInput, { target: { value: 'a'.repeat(960) } });
    const counter = screen.getByText('960 / 1000');
    expect(counter.style.color).toBe('var(--color-danger, #ef4444)');
  });

  it('the bio counter stays muted at or below 950 characters', async () => {
    renderPage();
    const bioInput = await screen.findByPlaceholderText('Tell others about yourself...');
    fireEvent.change(bioInput, { target: { value: 'a'.repeat(950) } });
    const counter = screen.getByText('950 / 1000');
    expect(counter.style.color).toBe('var(--text-muted)');
  });

  it('submits fullName/email/bio only when no new password is given', async () => {
    renderPage();
    await screen.findByText('Preferences');
    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));
    await waitFor(() => expect(api.updateProfile).toHaveBeenCalled());
    expect(api.updateProfile).toHaveBeenCalledWith({
      fullName: 'Test User', email: 'test@example.com', bio: '',
    });
  });

  it('rejects mismatched new/confirm passwords without calling the API', async () => {
    renderPage();
    await screen.findByText('Preferences');
    const { newPw, confirmPw } = getPasswordInputs();
    fireEvent.change(newPw, { target: { value: 'newpass1' } });
    fireEvent.change(confirmPw, { target: { value: 'different' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));

    expect(await screen.findByText('New passwords do not match')).toBeInTheDocument();
    expect(api.updateProfile).not.toHaveBeenCalled();
  });

  it('includes currentPassword + newPassword in the payload when they match', async () => {
    renderPage();
    await screen.findByText('Preferences');
    const { current, newPw, confirmPw } = getPasswordInputs();
    fireEvent.change(current, { target: { value: 'oldpass' } });
    fireEvent.change(newPw, { target: { value: 'newpass1' } });
    fireEvent.change(confirmPw, { target: { value: 'newpass1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));

    await waitFor(() => expect(api.updateProfile).toHaveBeenCalled());
    expect(api.updateProfile).toHaveBeenCalledWith({
      fullName: 'Test User', email: 'test@example.com', bio: '',
      currentPassword: 'oldpass', newPassword: 'newpass1',
    });
  });

  it('shows a success message and clears password fields after a successful save', async () => {
    renderPage();
    await screen.findByText('Preferences');
    const { newPw, confirmPw } = getPasswordInputs();
    fireEvent.change(newPw, { target: { value: 'newpass1' } });
    fireEvent.change(confirmPw, { target: { value: 'newpass1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));

    expect(await screen.findByText('Profile updated successfully')).toBeInTheDocument();
    expect(newPw).toHaveValue('');
    expect(confirmPw).toHaveValue('');
  });

  it('shows the server error message (err.body.message) when the save fails', async () => {
    api.updateProfile.mockRejectedValue(Object.assign(new Error('generic'), { body: { message: 'email taken' } }));
    renderPage();
    await screen.findByText('Preferences');
    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));
    expect(await screen.findByText('email taken')).toBeInTheDocument();
  });

  it('falls back to err.message, then a generic message, when the save fails', async () => {
    api.updateProfile.mockRejectedValue(new Error('boom'));
    renderPage();
    await screen.findByText('Preferences');
    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));
    expect(await screen.findByText('boom')).toBeInTheDocument();
  });

  it('falls back to a generic failure message when the error carries nothing usable', async () => {
    api.updateProfile.mockRejectedValue(new Error());
    renderPage();
    await screen.findByText('Preferences');
    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));
    expect(await screen.findByText('Failed to update profile')).toBeInTheDocument();
  });
});

describe('UserPreferencesPage — delete account errors + cancel + navigation', () => {
  it('shows the server error message (err.body.message) when delete fails', async () => {
    api.deleteAccount.mockRejectedValue(Object.assign(new Error('generic'), { body: { message: 'cannot delete now' } }));
    renderPage();
    const trigger = await screen.findByTestId('delete-account-button');
    fireEvent.click(trigger);
    fireEvent.change(screen.getByTestId('delete-confirm-input'), { target: { value: 'testuser' } });
    fireEvent.click(screen.getByTestId('delete-confirm-button'));

    expect(await screen.findByTestId('delete-error')).toHaveTextContent('cannot delete now');
    expect(api.logout).not.toHaveBeenCalled();
  });

  it('falls back to a generic delete-failure message', async () => {
    api.deleteAccount.mockRejectedValue(new Error());
    renderPage();
    const trigger = await screen.findByTestId('delete-account-button');
    fireEvent.click(trigger);
    fireEvent.change(screen.getByTestId('delete-confirm-input'), { target: { value: 'testuser' } });
    fireEvent.click(screen.getByTestId('delete-confirm-button'));

    expect(await screen.findByTestId('delete-error')).toHaveTextContent('Failed to delete account');
  });

  it('a successful delete logs out and redirects to /wiki/Main', async () => {
    renderWithRoutes();
    const trigger = await screen.findByTestId('delete-account-button');
    fireEvent.click(trigger);
    fireEvent.change(screen.getByTestId('delete-confirm-input'), { target: { value: 'testuser' } });
    fireEvent.click(screen.getByTestId('delete-confirm-button'));

    await waitFor(() => expect(mockLogout).toHaveBeenCalled());
    expect(await screen.findByText('MAIN PAGE MARKER')).toBeInTheDocument();
  });

  it('Cancel resets the delete section state', async () => {
    renderPage();
    const trigger = await screen.findByTestId('delete-account-button');
    fireEvent.click(trigger);
    fireEvent.change(screen.getByTestId('delete-confirm-input'), { target: { value: 'partial' } });

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByTestId('delete-confirm-input')).not.toBeInTheDocument();
    // Re-open — confirm input should be back to empty, not "partial"
    fireEvent.click(screen.getByTestId('delete-account-button'));
    expect(screen.getByTestId('delete-confirm-input')).toHaveValue('');
  });
});

describe('UserPreferencesPage — display name', () => {
  const clickSave = () => fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));
  const rejection = (status, message) =>
    Object.assign(new Error('generic'), { status, body: { message } });

  it('labels the editable field "Display name" and shows the login name read-only', async () => {
    renderPage();
    const display = await screen.findByLabelText('Display name');
    expect(display).toHaveValue('Test User');
    expect(display).not.toHaveAttribute('readonly');
    expect(display).toHaveAttribute('aria-required', 'true');
    const login = screen.getByLabelText('Login name');
    expect(login).toHaveValue('testuser');
    expect(login).toHaveAttribute('readonly');
    expect(screen.queryByText('Full Name')).not.toBeInTheDocument();
  });

  it.each([
    ['', 'Display name is required'],
    ['   ', 'Display name is required'],
    ['x', 'Display name must be at least 2 characters'],
    ['a'.repeat(65), 'Display name must be 64 characters or fewer'],
    ['me@example.com', "Display name cannot contain '@'"],
  ])('rejects %j on save without calling the API', async (value, message) => {
    renderPage();
    fireEvent.change(await screen.findByLabelText('Display name'), { target: { value } });
    clickSave();

    expect(await screen.findByTestId('display-name-error')).toHaveTextContent(message);
    expect(screen.getByLabelText('Display name')).toHaveAttribute('aria-invalid', 'true');
    expect(api.updateProfile).not.toHaveBeenCalled();
  });

  it('shows a client-side rule as soon as the field is left, before any save', async () => {
    renderPage();
    const display = await screen.findByLabelText('Display name');
    fireEvent.change(display, { target: { value: 'x' } });
    expect(screen.queryByTestId('display-name-error')).not.toBeInTheDocument();

    fireEvent.blur(display);

    expect(screen.getByTestId('display-name-error'))
      .toHaveTextContent('Display name must be at least 2 characters');
  });

  it('does not re-validate an unchanged legacy name, so it never blocks saving the bio', async () => {
    api.getProfile.mockResolvedValue({ ...FAKE_PROFILE, fullName: 'legacy@example.com' });
    api.updateProfile.mockResolvedValue({ ...FAKE_PROFILE, fullName: 'legacy@example.com', bio: 'hi' });
    renderPage();
    await screen.findByDisplayValue('legacy@example.com');
    fireEvent.change(screen.getByPlaceholderText('Tell others about yourself...'), { target: { value: 'hi' } });
    clickSave();

    await waitFor(() => expect(api.updateProfile).toHaveBeenCalledWith({
      fullName: 'legacy@example.com', email: 'test@example.com', bio: 'hi',
    }));
    expect(screen.queryByTestId('display-name-error')).not.toBeInTheDocument();
  });

  it('saves the trimmed name, shows the stored one and refreshes the session user', async () => {
    api.updateProfile.mockResolvedValue({ ...FAKE_PROFILE, fullName: 'New Name' });
    renderPage();
    const display = await screen.findByLabelText('Display name');
    fireEvent.change(display, { target: { value: '  New Name  ' } });
    clickSave();

    await waitFor(() => expect(api.updateProfile).toHaveBeenCalledWith({
      fullName: 'New Name', email: 'test@example.com', bio: '',
    }));
    expect(await screen.findByText('Profile updated successfully')).toBeInTheDocument();
    expect(display).toHaveValue('New Name');
    expect(mockRefresh).toHaveBeenCalledTimes(1);
  });

  it('puts the server 409 message under the field, not in the page banner', async () => {
    api.updateProfile.mockRejectedValue(rejection(409, 'That display name is already used by another account'));
    renderPage();
    fireEvent.change(await screen.findByLabelText('Display name'), { target: { value: 'Taken Name' } });
    clickSave();

    expect(await screen.findByTestId('display-name-error'))
      .toHaveTextContent('That display name is already used by another account');
    expect(document.querySelector('.error-banner')).toBeNull();
    expect(mockRefresh).not.toHaveBeenCalled();
  });

  it('puts a server 400 under the field when the display name is the only change', async () => {
    api.updateProfile.mockRejectedValue(rejection(400, "The name 'Admin' is reserved for a role or group"));
    renderPage();
    fireEvent.change(await screen.findByLabelText('Display name'), { target: { value: 'Admin' } });
    clickSave();

    expect(await screen.findByTestId('display-name-error'))
      .toHaveTextContent("The name 'Admin' is reserved for a role or group");
    expect(document.querySelector('.error-banner')).toBeNull();
  });

  it('keeps a server 400 in the banner when a password change was part of the request', async () => {
    api.updateProfile.mockRejectedValue(rejection(400, 'Password is too common'));
    renderPage();
    fireEvent.change(await screen.findByLabelText('Display name'), { target: { value: 'New Name' } });
    const { current, newPw, confirmPw } = getPasswordInputs();
    fireEvent.change(current, { target: { value: 'oldpass' } });
    fireEvent.change(newPw, { target: { value: 'newpass1' } });
    fireEvent.change(confirmPw, { target: { value: 'newpass1' } });
    clickSave();

    expect(await screen.findByText('Password is too common')).toBeInTheDocument();
    expect(screen.queryByTestId('display-name-error')).not.toBeInTheDocument();
  });

  it('clears the server message once the name is edited again', async () => {
    api.updateProfile.mockRejectedValue(rejection(409, 'That display name is already used by another account'));
    renderPage();
    const display = await screen.findByLabelText('Display name');
    fireEvent.change(display, { target: { value: 'Taken Name' } });
    clickSave();
    await screen.findByTestId('display-name-error');

    fireEvent.change(display, { target: { value: 'Another Name' } });

    expect(screen.queryByTestId('display-name-error')).not.toBeInTheDocument();
  });
});
