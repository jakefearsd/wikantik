import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

vi.mock('../api/client', () => ({
  api: { getPageTemplates: vi.fn(), listClusters: vi.fn(), listPages: vi.fn() },
}));
const mockNavigate = vi.fn();
vi.mock('../navigation/NavigationGuardProvider', () => ({ useGuardedNavigate: () => mockNavigate }));

import NewArticleModal from './NewArticleModal';
import { api } from '../api/client';

const FIVE = [
  { type: 'article', label: 'Article', description: 'A general article', metadata: { type: 'article', status: 'active' }, body: '# {{title}}\n\n## Overview\n' },
  { type: 'reference', label: 'Reference', description: 'Reference', metadata: { type: 'reference', status: 'active' }, body: '# {{title}}\n\n## Summary\n' },
  { type: 'design', label: 'Design', description: 'Design doc', metadata: { type: 'design', status: 'active' }, body: '# {{title}}\n\n## Alternatives considered\n' },
  { type: 'runbook', label: 'Runbook', description: 'Runbook', metadata: { type: 'runbook', status: 'active', runbook: { steps: ['a', 'b'] } }, body: '# {{title}}\n\n## Notes\n' },
  { type: 'hub', label: 'Hub', description: 'Hub', metadata: { type: 'hub', status: 'active' }, body: '# {{title}}\n\n## Pages\n' },
];

function renderModal(overrides = {}) {
  const props = { isOpen: true, onClose: vi.fn(), ...overrides };
  return { ...render(<NewArticleModal {...props} />), props };
}

describe('NewArticleModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getPageTemplates.mockResolvedValue({ templates: FIVE });
    api.listClusters.mockResolvedValue({ clusters: [{ name: 'finance' }] });
    api.listPages.mockResolvedValue({ pages: [] });
  });

  it('renders nothing when isOpen is false', () => {
    renderModal({ isOpen: false });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('has role="dialog" when open', async () => {
    renderModal();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    await screen.findByRole('button', { name: 'Hub' });
  });

  it('pressing Esc calls onClose', async () => {
    const { props } = renderModal();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(props.onClose).toHaveBeenCalled();
    await screen.findByRole('button', { name: 'Hub' });
  });

  it('typing a title auto-fills the page name slug', async () => {
    renderModal();
    fireEvent.change(screen.getByLabelText(/title/i), { target: { value: 'My Test Article' } });
    expect(screen.getByDisplayValue('MyTestArticle')).toBeInTheDocument();
    await screen.findByRole('button', { name: 'Hub' });
  });

  it('Cancel button calls onClose', async () => {
    const { props } = renderModal();
    fireEvent.click(screen.getByRole('button', { name: /Cancel/ }));
    expect(props.onClose).toHaveBeenCalled();
    await screen.findByRole('button', { name: 'Hub' });
  });

  it('offers all five types and previews the selected template', async () => {
    renderModal();
    for (const label of ['Article', 'Reference', 'Design', 'Runbook', 'Hub']) {
      expect(await screen.findByRole('button', { name: label })).toBeInTheDocument();
    }
    fireEvent.click(screen.getByRole('button', { name: 'Design' }));
    fireEvent.change(screen.getByLabelText(/title/i), { target: { value: 'New Engine' } });
    expect(screen.getByTestId('template-preview')).toHaveTextContent('# New Engine');
    expect(screen.getByTestId('template-preview')).toHaveTextContent('## Alternatives considered');
  });

  it('prefills the title it was opened with', async () => {
    renderModal({ initialTitle: 'Index Fund' });
    expect(await screen.findByDisplayValue('Index Fund')).toBeInTheDocument();
    expect(screen.getByDisplayValue('IndexFund')).toBeInTheDocument(); // derived slug
  });

  it('requires a cluster for a hub', async () => {
    renderModal({ initialTitle: 'Finance' });
    fireEvent.click(await screen.findByRole('button', { name: 'Hub' }));
    expect(screen.getByRole('button', { name: /create/i })).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/cluster/i), { target: { value: 'finance' } });
    expect(screen.getByRole('button', { name: /create/i })).toBeEnabled();
  });

  it('navigates to the editor with the filled template', async () => {
    renderModal({ initialTitle: 'Restart X' });
    fireEvent.click(await screen.findByRole('button', { name: 'Runbook' }));
    fireEvent.click(screen.getByRole('button', { name: /create/i }));
    expect(mockNavigate).toHaveBeenCalledWith('/edit/RestartX', { state: {
      initialMetadata: expect.objectContaining({ type: 'runbook', runbook: expect.any(Object) }),
      initialContent: '# Restart X\n\n## Notes\n' } });
  });

  it('detects an existing page by asking the server, not a client-side page list', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: 'Existing' }] });
    renderModal({ initialTitle: 'Existing' });
    expect(await screen.findByText(/already exists/i)).toBeInTheDocument();
    expect(api.listPages).toHaveBeenCalledWith({ names: ['Existing'], limit: 1 });
  });

  it('falls back and says so when templates cannot be loaded', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.getPageTemplates.mockRejectedValue(new Error('down'));
    renderModal({ initialTitle: 'Plain' });
    expect(await screen.findByText('Templates unavailable')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /create/i }));
    expect(mockNavigate).toHaveBeenCalledWith('/edit/Plain', { state: expect.objectContaining({ initialContent: '# Plain\n\n' }) });
  });
});
