import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { useFrontmatterValidation } from './useFrontmatterValidation';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const ERR = { violations: [{ field: 'type', severity: 'ERROR', code: 'x', message: 'bad' }] };
const tick = (ms) => new Promise((r) => setTimeout(r, ms));

describe('useFrontmatterValidation', () => {
  it('debounces: many rapid edits coalesce into one validate call', async () => {
    const validate = vi.fn().mockResolvedValue({ violations: [] });
    const { rerender } = renderHook(({ m }) => useFrontmatterValidation(m, { validate, debounceMs: 400 }), {
      initialProps: { m: { title: 'a' } },
    });
    rerender({ m: { title: 'ab' } });
    rerender({ m: { title: 'abc' } });
    expect(validate).not.toHaveBeenCalled();
    await act(async () => { vi.advanceTimersByTime(400); });
    expect(validate).toHaveBeenCalledTimes(1);
  });

  it('drops a stale response when a newer request has started (race safety)', async () => {
    vi.useRealTimers();
    let resolveFirst;
    const validate = vi
      .fn()
      .mockImplementationOnce(() => new Promise((r) => { resolveFirst = r; }))
      .mockResolvedValueOnce(ERR);
    const { result, rerender } = renderHook(({ m }) => useFrontmatterValidation(m, { validate, debounceMs: 5 }), {
      initialProps: { m: { title: 'a' } },
    });
    await waitFor(() => expect(validate).toHaveBeenCalledTimes(1)); // request #1 fired, pending
    rerender({ m: { title: 'b' } });
    await waitFor(() => expect(validate).toHaveBeenCalledTimes(2)); // request #2 fired + resolves ERR
    await waitFor(() => expect(result.current.violations).toEqual(ERR.violations));
    await act(async () => { resolveFirst({ violations: [] }); await tick(10); }); // late #1 resolves empty
    expect(result.current.violations).toEqual(ERR.violations); // stale #1 ignored
  });

  it('fails open: a rejected validate keeps the last violations and never throws', async () => {
    vi.useRealTimers();
    const validate = vi.fn().mockResolvedValueOnce(ERR).mockRejectedValueOnce(new Error('net'));
    const { result, rerender } = renderHook(({ m }) => useFrontmatterValidation(m, { validate, debounceMs: 5 }), {
      initialProps: { m: { title: 'a' } },
    });
    await waitFor(() => expect(result.current.violations).toEqual(ERR.violations));
    rerender({ m: { title: 'b' } });
    await waitFor(() => expect(validate).toHaveBeenCalledTimes(2));
    await act(async () => { await tick(10); });
    expect(result.current.violations).toEqual(ERR.violations); // outage did not clear or block
  });
});
