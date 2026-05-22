import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import PolicyGrantFormModal from './PolicyGrantFormModal';

// Contract: { grant, isOpen, onClose, onSave }. onSave(data, idOrUndefined).
// data = { principalType, principalName, permissionType, target, actions }.

const principalNameInput = (container) =>
  container.querySelector('input[type="text"]');

describe('PolicyGrantFormModal — gating and modes', () => {
  it('renders nothing when closed', () => {
    const { container } = render(<PolicyGrantFormModal isOpen={false} onClose={vi.fn()} onSave={vi.fn()} />);
    expect(container.firstChild).toBeNull();
  });

  it('create mode: default role/page selectors and action checkboxes', () => {
    render(<PolicyGrantFormModal isOpen onClose={vi.fn()} onSave={vi.fn()} />);
    expect(screen.getByText('Create Policy Grant')).toBeTruthy();
    // Page action options are visible by default.
    expect(screen.getByText('view')).toBeTruthy();
    expect(screen.getByText('edit')).toBeTruthy();
    expect(screen.getByText('delete')).toBeTruthy();
  });

  it('edit mode: prefills principal, target, and parses actions', () => {
    const { container } = render(
      <PolicyGrantFormModal
        isOpen
        grant={{ id: 7, principalType: 'role', principalName: 'Authenticated', permissionType: 'page', target: 'Foo*', actions: 'view, edit' }}
        onClose={vi.fn()}
        onSave={vi.fn()}
      />
    );
    expect(screen.getByText('Edit Policy Grant')).toBeTruthy();
    expect(principalNameInput(container).value).toBe('Authenticated');
    // Parsed actions are checked.
    const viewCb = screen.getByText('view').querySelector('input[type="checkbox"]');
    const editCb = screen.getByText('edit').querySelector('input[type="checkbox"]');
    const deleteCb = screen.getByText('delete').querySelector('input[type="checkbox"]');
    expect(viewCb.checked).toBe(true);
    expect(editCb.checked).toBe(true);
    expect(deleteCb.checked).toBe(false);
  });

  it('edit mode with AllPermission grant hides action checkboxes and checks AllPermission', () => {
    render(
      <PolicyGrantFormModal
        isOpen
        grant={{ id: 9, principalType: 'role', principalName: 'Admin', permissionType: 'all', target: '*', actions: '*' }}
        onClose={vi.fn()}
        onSave={vi.fn()}
      />
    );
    // Action checkboxes are not rendered when AllPermission is on.
    expect(screen.queryByText('view')).toBeNull();
    const allCb = screen.getByText(/Grant AllPermission/).querySelector('input[type="checkbox"]');
    expect(allCb.checked).toBe(true);
  });
});

describe('PolicyGrantFormModal — interactions', () => {
  it('selecting actions and submitting sends a comma-joined actions payload', async () => {
    const onSave = vi.fn().mockResolvedValue();
    const onClose = vi.fn();
    const { container } = render(<PolicyGrantFormModal isOpen onClose={onClose} onSave={onSave} />);

    fireEvent.change(principalNameInput(container), { target: { value: 'Authenticated' } });
    fireEvent.click(screen.getByText('view').querySelector('input'));
    fireEvent.click(screen.getByText('edit').querySelector('input'));

    fireEvent.click(screen.getByRole('button', { name: 'Create Grant' }));

    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith(
        {
          principalType: 'role',
          principalName: 'Authenticated',
          permissionType: 'page',
          target: '*',
          actions: 'view,edit',
        },
        undefined
      )
    );
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('toggling AllPermission submits permissionType=all, target=*, actions=*', async () => {
    const onSave = vi.fn().mockResolvedValue();
    const { container } = render(<PolicyGrantFormModal isOpen onClose={vi.fn()} onSave={onSave} />);
    fireEvent.change(principalNameInput(container), { target: { value: 'Admin' } });
    fireEvent.click(screen.getByText(/Grant AllPermission/).querySelector('input'));
    fireEvent.click(screen.getByRole('button', { name: 'Create Grant' }));
    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith(
        expect.objectContaining({ permissionType: 'all', target: '*', actions: '*', principalName: 'Admin' }),
        undefined
      )
    );
  });

  it('switching permission type to wiki swaps the available actions', () => {
    render(<PolicyGrantFormModal isOpen onClose={vi.fn()} onSave={vi.fn()} />);
    // The Permission Type select is the one carrying a "Wiki" option.
    const selects = screen.getAllByRole('combobox');
    const permSelect = selects.find((s) =>
      s.querySelector('option[value="wiki"]') != null
    );
    expect(permSelect).toBeTruthy();
    // Page actions visible before the switch.
    expect(screen.getByText('upload')).toBeTruthy();
    fireEvent.change(permSelect, { target: { value: 'wiki' } });
    expect(screen.getByText('createPages')).toBeTruthy();
    expect(screen.getByText('login')).toBeTruthy();
    expect(screen.queryByText('upload')).toBeNull();
  });

  it('edits an existing grant: onSave receives the grant id', async () => {
    const onSave = vi.fn().mockResolvedValue();
    render(
      <PolicyGrantFormModal
        isOpen
        grant={{ id: 42, principalType: 'user', principalName: 'bob', permissionType: 'page', target: '*', actions: 'view' }}
        onClose={vi.fn()}
        onSave={onSave}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));
    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ principalName: 'bob' }), 42)
    );
  });
});

describe('PolicyGrantFormModal — error and cancel', () => {
  it('shows an error and stays open when onSave rejects', async () => {
    const onSave = vi.fn().mockRejectedValue(new Error('conflict'));
    const onClose = vi.fn();
    const { container } = render(<PolicyGrantFormModal isOpen onClose={onClose} onSave={onSave} />);
    fireEvent.change(principalNameInput(container), { target: { value: 'X' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create Grant' }));
    await screen.findByText('conflict');
    expect(onClose).not.toHaveBeenCalled();
  });

  it('Cancel calls onClose without saving', () => {
    const onSave = vi.fn();
    const onClose = vi.fn();
    render(<PolicyGrantFormModal isOpen onClose={onClose} onSave={onSave} />);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalled();
    expect(onSave).not.toHaveBeenCalled();
  });
});
