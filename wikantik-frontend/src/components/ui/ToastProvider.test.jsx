import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, renderHook, screen, render, fireEvent } from '@testing-library/react';
import { ToastProvider } from './ToastProvider';
import { useToast } from '../../hooks/useToast';

const wrapper = ({ children }) => <ToastProvider>{children}</ToastProvider>;

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

// Helper: render a small consumer that exposes useToast into result.current
function renderToast() {
  return renderHook(() => useToast(), { wrapper });
}

describe('ToastProvider', () => {
  it('1. calling success("Saved") renders a toast showing "Saved"', async () => {
    const { result } = renderToast();

    act(() => { result.current.success('Saved'); });

    expect(screen.getByText('Saved')).toBeTruthy();
  });

  it('2. success toast auto-dismisses after 5000ms', async () => {
    const { result } = renderToast();

    act(() => { result.current.success('Temporary'); });
    expect(screen.getByText('Temporary')).toBeTruthy();

    act(() => { vi.advanceTimersByTime(5001); });

    expect(screen.queryByText('Temporary')).toBeNull();
  });

  it('3. error toast persists past 5000ms', async () => {
    const { result } = renderToast();

    act(() => { result.current.error('Critical failure'); });

    act(() => { vi.advanceTimersByTime(10000); });

    expect(screen.getByText('Critical failure')).toBeTruthy();
  });

  it('4. dedupe: calling success("X") twice only renders one toast', async () => {
    const { result } = renderToast();

    act(() => {
      result.current.success('X');
      result.current.success('X');
    });

    const toasts = screen.getAllByText('X');
    expect(toasts).toHaveLength(1);
  });

  it('5. stack cap: adding 5 distinct toasts leaves only 4 in the DOM', async () => {
    const { result } = renderToast();

    act(() => {
      result.current.info('Toast A');
      result.current.info('Toast B');
      result.current.info('Toast C');
      result.current.info('Toast D');
      result.current.info('Toast E');
    });

    // Oldest (A) should be dropped
    expect(screen.queryByText('Toast A')).toBeNull();
    expect(screen.getByText('Toast B')).toBeTruthy();
    expect(screen.getByText('Toast C')).toBeTruthy();
    expect(screen.getByText('Toast D')).toBeTruthy();
    expect(screen.getByText('Toast E')).toBeTruthy();
  });

  it('6. clicking the dismiss button removes that toast', async () => {
    const { result } = renderToast();

    act(() => { result.current.success('Dismissable'); });
    expect(screen.getByText('Dismissable')).toBeTruthy();

    const dismissBtn = screen.getByRole('button', { name: 'Dismiss' });
    act(() => { fireEvent.click(dismissBtn); });

    expect(screen.queryByText('Dismissable')).toBeNull();
  });

  it('7. error toast has role="alert"; success toast has role="status"', async () => {
    const { result } = renderToast();

    act(() => {
      result.current.error('Something went wrong');
    });
    expect(screen.getByRole('alert')).toBeTruthy();

    // clear the error (dismiss it manually via dismiss fn or just add a success)
    const dismissBtn = screen.getByRole('button', { name: 'Dismiss' });
    act(() => { fireEvent.click(dismissBtn); });

    act(() => {
      result.current.success('All good');
    });
    expect(screen.getByRole('status')).toBeTruthy();
  });

  it('useToast throws when used outside ToastProvider', () => {
    // Suppress React error boundary console noise
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => renderHook(() => useToast())).toThrow('useToast must be used within ToastProvider');
    spy.mockRestore();
  });
});
