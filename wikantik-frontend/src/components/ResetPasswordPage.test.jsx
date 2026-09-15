import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../api/client', () => ({
  api: { resetPassword: vi.fn() },
}));

import ResetPasswordPage from './ResetPasswordPage';
import { api } from '../api/client';

const renderPage = () =>
  render(
    <MemoryRouter>
      <ResetPasswordPage />
    </MemoryRouter>
  );

beforeEach(() => {
  vi.clearAllMocks();
});

describe('ResetPasswordPage', () => {
  it('renders the email field and submit button', () => {
    renderPage();
    expect(screen.getByPlaceholderText('you@example.com')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Send New Password' })).toBeInTheDocument();
  });

  it('shows a validation error and does not call the API when email is blank', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'Send New Password' }));

    expect(await screen.findByText('Please enter your email address')).toBeInTheDocument();
    expect(api.resetPassword).not.toHaveBeenCalled();
  });

  it('shows a validation error for a whitespace-only email', async () => {
    renderPage();
    fireEvent.change(screen.getByPlaceholderText('you@example.com'), { target: { value: '   ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send New Password' }));

    expect(await screen.findByText('Please enter your email address')).toBeInTheDocument();
    expect(api.resetPassword).not.toHaveBeenCalled();
  });

  it('submits the email and shows the success confirmation', async () => {
    api.resetPassword.mockResolvedValue({});
    renderPage();

    fireEvent.change(screen.getByPlaceholderText('you@example.com'), { target: { value: 'user@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send New Password' }));

    expect(await screen.findByText('Check your email')).toBeInTheDocument();
    expect(api.resetPassword).toHaveBeenCalledWith('user@example.com');
    expect(screen.getByRole('link', { name: 'Return to wiki' })).toHaveAttribute('href', '/wiki/Main');
    // The form is replaced by the confirmation panel.
    expect(screen.queryByPlaceholderText('you@example.com')).not.toBeInTheDocument();
  });

  it('surfaces a server error message from err.body.message', async () => {
    const err = new Error('generic failure');
    err.body = { message: 'No account with that email' };
    api.resetPassword.mockRejectedValue(err);
    renderPage();

    fireEvent.change(screen.getByPlaceholderText('you@example.com'), { target: { value: 'user@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send New Password' }));

    expect(await screen.findByText('No account with that email')).toBeInTheDocument();
  });

  it('falls back to err.message, then a default message', async () => {
    api.resetPassword.mockRejectedValue(new Error('network down'));
    renderPage();

    fireEvent.change(screen.getByPlaceholderText('you@example.com'), { target: { value: 'user@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send New Password' }));
    expect(await screen.findByText('network down')).toBeInTheDocument();
  });

  it('shows the default error message when neither body nor message is present', async () => {
    api.resetPassword.mockRejectedValue({});
    renderPage();

    fireEvent.change(screen.getByPlaceholderText('you@example.com'), { target: { value: 'user@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send New Password' }));
    expect(await screen.findByText('An error occurred. Please try again.')).toBeInTheDocument();
  });

  it('shows a loading state on the submit button while the request is pending', async () => {
    let resolveFn;
    api.resetPassword.mockReturnValue(new Promise((resolve) => { resolveFn = resolve; }));
    renderPage();

    fireEvent.change(screen.getByPlaceholderText('you@example.com'), { target: { value: 'user@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send New Password' }));

    expect(await screen.findByRole('button', { name: 'Sending...' })).toBeDisabled();

    resolveFn({});
    expect(await screen.findByText('Check your email')).toBeInTheDocument();
  });

  it('has a "Back to wiki" link on the form', () => {
    renderPage();
    expect(screen.getByRole('link', { name: 'Back to wiki' })).toHaveAttribute('href', '/wiki/Main');
  });
});
