import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../hooks/useAuth', () => ({
  useAuth: vi.fn(),
}));

vi.mock('./SsoLoginButton', () => ({
  default: () => null,
}));

import LoginForm from './LoginForm';
import { useAuth } from '../hooks/useAuth';

function renderForm(overrides = {}) {
  const props = {
    onClose: vi.fn(),
    ...overrides,
  };
  return { ...render(<MemoryRouter><LoginForm {...props} /></MemoryRouter>), props };
}

describe('LoginForm (#30)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuth.mockReturnValue({ login: vi.fn().mockResolvedValue(undefined) });
  });

  it('has role="dialog" (uses Modal shell)', () => {
    renderForm();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('pressing Esc calls onClose', () => {
    const { props } = renderForm();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(props.onClose).toHaveBeenCalled();
  });

  it('has username and password fields with correct testids', () => {
    renderForm();
    expect(screen.getByTestId('login-username')).toBeInTheDocument();
    expect(screen.getByTestId('login-password')).toBeInTheDocument();
  });

  it('shows error on failed login', async () => {
    useAuth.mockReturnValue({ login: vi.fn().mockRejectedValue(new Error('bad')) });
    renderForm();
    fireEvent.change(screen.getByTestId('login-username'), { target: { value: 'user' } });
    fireEvent.change(screen.getByTestId('login-password'), { target: { value: 'wrong' } });
    fireEvent.click(screen.getByTestId('login-submit'));
    await waitFor(() => expect(screen.getByTestId('login-error')).toBeInTheDocument());
    expect(screen.getByTestId('login-error')).toHaveTextContent('Invalid credentials');
  });

  it('calls login and onClose on successful submit', async () => {
    const loginFn = vi.fn().mockResolvedValue(undefined);
    useAuth.mockReturnValue({ login: loginFn });
    const { props } = renderForm();
    fireEvent.change(screen.getByTestId('login-username'), { target: { value: 'alice' } });
    fireEvent.change(screen.getByTestId('login-password'), { target: { value: 'secret' } });
    fireEvent.click(screen.getByTestId('login-submit'));
    await waitFor(() => expect(props.onClose).toHaveBeenCalled());
    expect(loginFn).toHaveBeenCalledWith('alice', 'secret');
  });
});
