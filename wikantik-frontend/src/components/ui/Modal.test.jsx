import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import Modal from './Modal';
import { _resetLockCount } from '../../hooks/useScrollLock';

beforeEach(() => {
  _resetLockCount();
  // Remove any leftover portal root between tests
  const existing = document.getElementById('modal-root');
  if (existing) existing.remove();
});

afterEach(() => {
  const existing = document.getElementById('modal-root');
  if (existing) existing.remove();
  _resetLockCount();
  document.body.style.overflow = '';
  document.body.style.paddingRight = '';
});

describe('Modal', () => {
  it('renders nothing when isOpen is false', () => {
    render(
      <Modal isOpen={false} onClose={() => {}} labelledBy="dlg-title">
        <span>Content</span>
      </Modal>,
    );
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.queryByText('Content')).toBeNull();
  });

  it('renders dialog with correct ARIA attributes when open', () => {
    render(
      <Modal isOpen={true} onClose={() => {}} labelledBy="my-title">
        <span>Hello</span>
      </Modal>,
    );
    const dialog = screen.getByRole('dialog');
    expect(dialog).toBeTruthy();
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(dialog.getAttribute('aria-labelledby')).toBe('my-title');
  });

  it('calls onClose when Escape is pressed', () => {
    const onClose = vi.fn();
    render(
      <Modal isOpen={true} onClose={onClose} labelledBy="dlg-title">
        <span>Content</span>
      </Modal>,
    );
    act(() => {
      fireEvent.keyDown(document, { key: 'Escape' });
    });
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('calls onClose when overlay/backdrop is clicked', () => {
    const onClose = vi.fn();
    render(
      <Modal isOpen={true} onClose={onClose} labelledBy="dlg-title">
        <span>Content</span>
      </Modal>,
    );
    // Click the overlay (the portal root child with class modal-overlay)
    const overlay = document.querySelector('.modal-overlay');
    expect(overlay).toBeTruthy();
    act(() => { fireEvent.click(overlay); });
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('does NOT call onClose when clicking inside modal content', () => {
    const onClose = vi.fn();
    render(
      <Modal isOpen={true} onClose={onClose} labelledBy="dlg-title">
        <button>Inner Button</button>
      </Modal>,
    );
    const innerBtn = screen.getByText('Inner Button');
    act(() => { fireEvent.click(innerBtn); });
    expect(onClose).not.toHaveBeenCalled();
  });

  it('portals the modal into #modal-root', () => {
    render(
      <Modal isOpen={true} onClose={() => {}} labelledBy="dlg-title">
        <span>Portaled</span>
      </Modal>,
    );
    const modalRoot = document.getElementById('modal-root');
    expect(modalRoot).toBeTruthy();
    expect(modalRoot.querySelector('[role="dialog"]')).toBeTruthy();
  });

  it('applies optional className to the content element', () => {
    render(
      <Modal isOpen={true} onClose={() => {}} labelledBy="dlg-title" className="my-modal">
        <span>Content</span>
      </Modal>,
    );
    const dialog = screen.getByRole('dialog');
    expect(dialog.classList.contains('my-modal')).toBe(true);
  });

  it('applies optional style to the content element', () => {
    render(
      <Modal isOpen={true} onClose={() => {}} labelledBy="dlg-title" style={{ maxWidth: '480px' }}>
        <span>Content</span>
      </Modal>,
    );
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveStyle({ maxWidth: '480px' });
  });
});
