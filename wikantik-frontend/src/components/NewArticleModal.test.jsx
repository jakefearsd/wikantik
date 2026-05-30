import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import NewArticleModal from './NewArticleModal';

function renderModal(overrides = {}) {
  const props = {
    isOpen: true,
    onClose: vi.fn(),
    existingPageNames: new Set(['existing-page']),
    existingClusters: ['tech', 'news'],
    ...overrides,
  };
  return { ...render(<MemoryRouter><NewArticleModal {...props} /></MemoryRouter>), props };
}

describe('NewArticleModal (#29)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders nothing when isOpen is false', () => {
    renderModal({ isOpen: false });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('has role="dialog" when open', () => {
    renderModal();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('pressing Esc calls onClose', () => {
    const { props } = renderModal();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(props.onClose).toHaveBeenCalled();
  });

  it('shows the New Article heading', () => {
    renderModal();
    expect(screen.getByText('New Article')).toBeInTheDocument();
  });

  it('Create button is disabled until a title is entered', () => {
    renderModal();
    const createBtn = screen.getByRole('button', { name: /Create|Open Editor/ });
    expect(createBtn).toBeDisabled();
  });

  it('typing a title auto-fills the page name slug', () => {
    renderModal();
    // The first textbox is the Title input
    const titleInput = screen.getAllByRole('textbox')[0];
    fireEvent.change(titleInput, { target: { value: 'My Test Article' } });
    // slug should have been generated (second textbox is slug)
    const slugInput = screen.getAllByRole('textbox')[1];
    expect(slugInput.value).toMatch(/My|Test|Article/);
  });

  it('Cancel button calls onClose', () => {
    const { props } = renderModal();
    fireEvent.click(screen.getByRole('button', { name: /Cancel/ }));
    expect(props.onClose).toHaveBeenCalled();
  });
});
