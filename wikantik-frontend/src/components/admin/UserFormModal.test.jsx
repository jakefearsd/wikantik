import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import UserFormModal from './UserFormModal';

// UserFormModal's contract: { user, isOpen, onClose, onSave }. The parent owns
// the API call; the modal invokes onSave(form) and then onClose() on success.
// The form's <label>s are not associated with inputs (no htmlFor/id), so we
// query fields positionally via the DOM rather than getByLabelText.

const fields = (container) => ({
  login: container.querySelector('input[type="text"]'),
  fullName: container.querySelectorAll('input[type="text"]')[1],
  email: container.querySelector('input[type="email"]'),
  bio: container.querySelector('textarea'),
  password: container.querySelector('input[type="password"]'),
});

describe('UserFormModal — open/close gating', () => {
  it('renders nothing when isOpen is false', () => {
    const { container } = render(
      <UserFormModal isOpen={false} onClose={vi.fn()} onSave={vi.fn()} />
    );
    expect(container.firstChild).toBeNull();
  });
});

describe('UserFormModal — create mode', () => {
  it('renders the create title with empty, enabled login field', () => {
    const { container } = render(<UserFormModal isOpen onClose={vi.fn()} onSave={vi.fn()} />);
    expect(screen.getByRole('heading', { name: 'Create User' })).toBeTruthy();
    const f = fields(container);
    expect(f.login.value).toBe('');
    expect(f.login.disabled).toBe(false);
    // Password is required in create mode.
    expect(f.password.required).toBe(true);
  });

  it('submits the assembled form to onSave then calls onClose', async () => {
    const onSave = vi.fn().mockResolvedValue();
    const onClose = vi.fn();
    const { container } = render(<UserFormModal isOpen onClose={onClose} onSave={onSave} />);
    const f = fields(container);

    fireEvent.change(f.login, { target: { value: 'newuser' } });
    fireEvent.change(f.fullName, { target: { value: 'New User' } });
    fireEvent.change(f.email, { target: { value: 'new@example.com' } });
    fireEvent.change(f.bio, { target: { value: 'hello' } });
    fireEvent.change(f.password, { target: { value: 'supersecret' } });

    fireEvent.click(screen.getByRole('button', { name: 'Create User' }));

    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith({
        loginName: 'newuser',
        fullName: 'New User',
        email: 'new@example.com',
        bio: 'hello',
        password: 'supersecret',
      })
    );
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('reflects the bio character counter', () => {
    const { container } = render(<UserFormModal isOpen onClose={vi.fn()} onSave={vi.fn()} />);
    fireEvent.change(fields(container).bio, { target: { value: 'abc' } });
    expect(screen.getByText('3 / 1000')).toBeTruthy();
  });
});

describe('UserFormModal — edit mode', () => {
  const USER = { loginName: 'alice', fullName: 'Alice A', email: 'alice@x.com', bio: 'an admin' };

  it('prefills fields, disables login name, and shows the edit title', () => {
    const { container } = render(<UserFormModal isOpen user={USER} onClose={vi.fn()} onSave={vi.fn()} />);
    expect(screen.getByText('Edit User')).toBeTruthy();
    const f = fields(container);
    expect(f.login.value).toBe('alice');
    expect(f.login.disabled).toBe(true);
    expect(f.fullName.value).toBe('Alice A');
    expect(f.email.value).toBe('alice@x.com');
    expect(f.bio.value).toBe('an admin');
    // Password starts blank and is NOT required in edit mode.
    expect(f.password.value).toBe('');
    expect(f.password.required).toBe(false);
  });

  it('submits the edited form (with blank password kept blank)', async () => {
    const onSave = vi.fn().mockResolvedValue();
    const { container } = render(<UserFormModal isOpen user={USER} onClose={vi.fn()} onSave={onSave} />);
    fireEvent.change(fields(container).fullName, { target: { value: 'Alice B' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));
    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith(expect.objectContaining({
        loginName: 'alice',
        fullName: 'Alice B',
        password: '',
      }))
    );
  });
});

describe('UserFormModal — cancel and error', () => {
  it('Cancel calls onClose without saving', () => {
    const onSave = vi.fn();
    const onClose = vi.fn();
    render(<UserFormModal isOpen onClose={onClose} onSave={onSave} />);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalled();
    expect(onSave).not.toHaveBeenCalled();
  });

  it('shows a generic error message and stays open when onSave rejects', async () => {
    const onSave = vi.fn().mockRejectedValue(new Error('email taken'));
    const onClose = vi.fn();
    const { container } = render(<UserFormModal isOpen onClose={onClose} onSave={onSave} />);
    const f = fields(container);
    fireEvent.change(f.login, { target: { value: 'u' } });
    fireEvent.change(f.password, { target: { value: 'longenough' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create User' }));
    await screen.findByText('email taken');
    expect(onClose).not.toHaveBeenCalled();
  });

  it('shows the session-expired message on a 403 error', async () => {
    const err = new Error('forbidden');
    err.status = 403;
    const onSave = vi.fn().mockRejectedValue(err);
    const { container } = render(<UserFormModal isOpen onClose={vi.fn()} onSave={onSave} />);
    const f = fields(container);
    fireEvent.change(f.login, { target: { value: 'u' } });
    fireEvent.change(f.password, { target: { value: 'longenough' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create User' }));
    await screen.findByText(/Session expired or not authorized/);
  });
});
