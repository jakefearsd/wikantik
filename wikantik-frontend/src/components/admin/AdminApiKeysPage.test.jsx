import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import AdminApiKeysPage from './AdminApiKeysPage';
import { api } from '../../api/client';

const activeKey = {
    id: 1,
    principalLogin: 'alice',
    label: 'laptop',
    scope: 'tools',
    fingerprint: 'abcdef012345',
    createdAt: '2026-04-01T10:00:00Z',
    createdBy: 'admin',
    lastUsedAt: '2026-04-18T12:00:00Z',
    revokedAt: null,
    revokedBy: null,
    active: true,
};

const revokedKey = {
    id: 2,
    principalLogin: 'bob',
    label: null,
    scope: 'mcp',
    fingerprint: 'ffffffffffff',
    createdAt: '2026-04-02T10:00:00Z',
    createdBy: 'admin',
    lastUsedAt: null,
    revokedAt: '2026-04-04T12:00:00Z',
    revokedBy: 'admin',
    active: false,
};

describe('AdminApiKeysPage', () => {
    beforeEach(() => {
        vi.spyOn(api.admin, 'listApiKeys').mockResolvedValue({
            keys: [activeKey, revokedKey],
        });
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('hides revoked keys by default and reveals them when the toggle flips', async () => {
        render(<AdminApiKeysPage />);

        await waitFor(() => expect(screen.getByText('alice')).toBeInTheDocument());
        expect(screen.queryByText('bob')).not.toBeInTheDocument();

        fireEvent.click(screen.getByLabelText(/Show revoked/i));
        expect(screen.getByText('bob')).toBeInTheDocument();
    });

    it('renders fingerprint but never the full hash', async () => {
        render(<AdminApiKeysPage />);
        await waitFor(() => expect(screen.getByText('alice')).toBeInTheDocument());
        expect(screen.getByText(/abcdef012345/)).toBeInTheDocument();
        expect(screen.queryByText(/abcdef0123456789/)).not.toBeInTheDocument();
    });

    it('shows Active/Revoked badges and hides Revoke button for revoked keys', async () => {
        render(<AdminApiKeysPage />);
        await waitFor(() => expect(screen.getByText('alice')).toBeInTheDocument());

        const activeRow = screen.getByText('alice').closest('tr');
        expect(within(activeRow).getByText('Active')).toBeInTheDocument();
        expect(within(activeRow).getByRole('button', { name: /Revoke/i })).toBeInTheDocument();

        fireEvent.click(screen.getByLabelText(/Show revoked/i));
        const revokedRow = screen.getByText('bob').closest('tr');
        expect(within(revokedRow).getByText('Revoked')).toBeInTheDocument();
        expect(within(revokedRow).queryByRole('button', { name: /Revoke/i })).not.toBeInTheDocument();
    });

    it('shows the empty-state message when no keys are returned', async () => {
        api.admin.listApiKeys.mockResolvedValueOnce({ keys: [] });
        render(<AdminApiKeysPage />);
        await waitFor(() => expect(screen.getByText(/No API keys found/i)).toBeInTheDocument());
    });

    it('surfaces a list error in the banner', async () => {
        api.admin.listApiKeys.mockRejectedValueOnce(new Error('boom'));
        render(<AdminApiKeysPage />);
        await waitFor(() => expect(screen.getByText('boom')).toBeInTheDocument());
    });

    it('generate flow reveals plaintext token exactly once and refreshes the list', async () => {
        const create = vi.spyOn(api.admin, 'createApiKey').mockResolvedValue({
            id: 99,
            principalLogin: 'alice',
            label: 'new',
            scope: 'tools',
            fingerprint: '0123456789ab',
            createdAt: '2026-04-19T09:00:00Z',
            active: true,
            token: 'wkk_plaintext-once',
        });

        render(<AdminApiKeysPage />);
        await waitFor(() => expect(screen.getByText('alice')).toBeInTheDocument());

        fireEvent.click(screen.getByRole('button', { name: /\+ Generate Key/i }));

        fireEvent.change(screen.getByPlaceholderText(/testbot/i), { target: { value: 'alice' } });
        fireEvent.change(screen.getByPlaceholderText(/OpenWebUI/i), { target: { value: 'new' } });
        fireEvent.change(screen.getByRole('combobox'), { target: { value: 'tools' } });

        fireEvent.click(screen.getByRole('button', { name: /^Generate$/i }));

        await waitFor(() => expect(create).toHaveBeenCalledWith({
            principalLogin: 'alice', label: 'new', scope: 'tools',
        }));

        // Plaintext reveal must be visible after create
        expect(screen.getByText('wkk_plaintext-once')).toBeInTheDocument();
        expect(screen.getByText(/only time you will see the plaintext/i)).toBeInTheDocument();

        // List reloads: listApiKeys called twice (initial + post-create)
        expect(api.admin.listApiKeys).toHaveBeenCalledTimes(2);

        // Dismissing the reveal hides the token
        fireEvent.click(screen.getByRole('button', { name: /I've saved it/i }));
        expect(screen.queryByText('wkk_plaintext-once')).not.toBeInTheDocument();
    });

    it('keeps the generate modal open and shows an error when the API rejects', async () => {
        vi.spyOn(api.admin, 'createApiKey').mockRejectedValue(
            Object.assign(new Error('principalLogin is required'), { status: 400 })
        );

        render(<AdminApiKeysPage />);
        await waitFor(() => expect(screen.getByText('alice')).toBeInTheDocument());

        fireEvent.click(screen.getByRole('button', { name: /\+ Generate Key/i }));
        fireEvent.change(screen.getByPlaceholderText(/testbot/i), { target: { value: 'x' } });
        fireEvent.click(screen.getByRole('button', { name: /^Generate$/i }));

        await waitFor(() => expect(screen.getByText(/principalLogin is required/i)).toBeInTheDocument());
        // Modal stays open after failure so the operator can retry.
        expect(screen.getByRole('button', { name: /^Generate$/i })).toBeInTheDocument();
    });

    it('revoke flow opens confirmation, calls API, and refreshes the list', async () => {
        const revoke = vi.spyOn(api.admin, 'revokeApiKey').mockResolvedValue({ success: true, id: 1 });

        render(<AdminApiKeysPage />);
        await waitFor(() => expect(screen.getByText('alice')).toBeInTheDocument());

        const row = screen.getByText('alice').closest('tr');
        fireEvent.click(within(row).getByRole('button', { name: /Revoke/i }));

        // Confirmation modal must identify the key being revoked
        expect(screen.getByText(/Revoke API Key/i)).toBeInTheDocument();
        expect(screen.getByText(/HTTP 403/)).toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: /Revoke Key/i }));

        await waitFor(() => expect(revoke).toHaveBeenCalledWith(1));
        expect(api.admin.listApiKeys).toHaveBeenCalledTimes(2);
    });

    it('cancel on the revoke dialog does not call the API', async () => {
        const revoke = vi.spyOn(api.admin, 'revokeApiKey').mockResolvedValue({ success: true });

        render(<AdminApiKeysPage />);
        await waitFor(() => expect(screen.getByText('alice')).toBeInTheDocument());

        const row = screen.getByText('alice').closest('tr');
        fireEvent.click(within(row).getByRole('button', { name: /Revoke/i }));
        fireEvent.click(screen.getByRole('button', { name: /Cancel/i }));

        expect(revoke).not.toHaveBeenCalled();
        expect(screen.queryByText(/Revoke API Key/i)).not.toBeInTheDocument();
    });
});
