import { describe, it, expect, vi, beforeEach } from 'vitest';
import { registerCommands, getCommands, getCommand, runCommand, subscribe, __resetRegistryForTest } from './registry';

describe('command registry', () => {
  beforeEach(() => __resetRegistryForTest());

  it('registers and unregisters a batch', () => {
    const off = registerCommands([{ id: 'a', title: 'A', run: () => {} }, { id: 'b', title: 'B', run: () => {} }]);
    expect(getCommands().map((c) => c.id)).toEqual(['a', 'b']);
    off();
    expect(getCommands()).toEqual([]);
  });

  it('a later registration of the same id wins, and the earlier unregister does not remove it', () => {
    const off1 = registerCommands([{ id: 'a', title: 'old', run: () => {} }]);
    registerCommands([{ id: 'a', title: 'new', run: () => {} }]);
    off1();
    expect(getCommand('a').title).toBe('new');
  });

  it('notifies subscribers and keeps a stable snapshot between changes', () => {
    const l = vi.fn();
    const unsub = subscribe(l);
    const before = getCommands();
    expect(getCommands()).toBe(before);
    registerCommands([{ id: 'x', title: 'X', run: () => {} }]);
    expect(l).toHaveBeenCalledTimes(1);
    unsub();
  });

  it('runCommand reports failures instead of throwing', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const onError = vi.fn();
    registerCommands([{ id: 'boom', title: 'Boom', run: () => { throw new Error('x'); } }]);
    await expect(runCommand('boom', { onError })).resolves.toBe(false);
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ id: 'boom' }), expect.any(Error));
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('runCommand resolves true on success and false for an unknown id', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    registerCommands([{ id: 'ok', title: 'OK', run: async () => {} }]);
    await expect(runCommand('ok')).resolves.toBe(true);
    await expect(runCommand('nope')).resolves.toBe(false);
    warn.mockRestore();
  });
});
