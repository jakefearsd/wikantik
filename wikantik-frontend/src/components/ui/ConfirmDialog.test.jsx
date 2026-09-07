import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import ConfirmDialog from './ConfirmDialog';

describe('ConfirmDialog', () => {
  it('renders the title and message', () => {
    render(<ConfirmDialog title="Delete Thing" message="Are you sure?" onConfirm={() => {}} onCancel={() => {}} />);
    expect(screen.getByText('Delete Thing')).toBeInTheDocument();
    expect(screen.getByText('Are you sure?')).toBeInTheDocument();
  });

  it('defaults to Continue/Cancel labels and a danger confirm button', () => {
    render(<ConfirmDialog message="Are you sure?" onConfirm={() => {}} onCancel={() => {}} />);
    const confirmBtn = screen.getByRole('button', { name: 'Continue' });
    expect(confirmBtn).toHaveClass('btn-danger');
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
  });

  it('supports custom confirm/cancel labels', () => {
    render(
      <ConfirmDialog
        message="Are you sure?"
        confirmLabel="Delete"
        cancelLabel="Nevermind"
        onConfirm={() => {}}
        onCancel={() => {}}
      />,
    );
    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Nevermind' })).toBeInTheDocument();
  });

  it('omits btn-danger when danger is false', () => {
    render(<ConfirmDialog message="ok?" danger={false} onConfirm={() => {}} onCancel={() => {}} />);
    expect(screen.getByRole('button', { name: 'Continue' })).not.toHaveClass('btn-danger');
  });

  it('calls onConfirm when the confirm button is clicked', () => {
    const onConfirm = vi.fn();
    render(<ConfirmDialog message="ok?" onConfirm={onConfirm} onCancel={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onConfirm).toHaveBeenCalled();
  });

  it('calls onCancel when the cancel button or overlay is clicked', () => {
    const onCancel = vi.fn();
    render(<ConfirmDialog message="ok?" onConfirm={() => {}} onCancel={onCancel} />);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('does not call onCancel when clicking inside the dialog content', () => {
    const onCancel = vi.fn();
    render(<ConfirmDialog title="Delete Thing" message="ok?" onConfirm={() => {}} onCancel={onCancel} />);
    fireEvent.click(screen.getByRole('dialog'));
    expect(onCancel).not.toHaveBeenCalled();
  });
});
