import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../api/work', () => ({ createQueue: vi.fn() }));
import { createQueue } from '../../api/work';
import { createStarterQueues } from './starterQueues';

beforeEach(() => {
  vi.clearAllMocks();
  createQueue.mockResolvedValue({});
});

describe('createStarterQueues', () => {
  it('creates implementer and review with the exact names and descriptions', async () => {
    const result = await createStarterQueues([]);
    expect(createQueue).toHaveBeenCalledWith({ key: 'implementer', name: 'Implementer', description: 'Ready work waiting for an agent to implement.' });
    expect(createQueue).toHaveBeenCalledWith({ key: 'review', name: 'Review', description: 'Completed work waiting for review.' });
    expect(result).toEqual({ created: ['implementer', 'review'], failed: [] });
  });

  it('skips a key that already exists, retired or not', async () => {
    const result = await createStarterQueues(['review']);
    expect(createQueue).toHaveBeenCalledTimes(1);
    expect(createQueue.mock.calls[0][0].key).toBe('implementer');
    expect(result).toEqual({ created: ['implementer'], failed: [] });
  });

  it('reports a failed create without throwing', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    createQueue.mockRejectedValueOnce(new Error('boom'));
    const result = await createStarterQueues([]);
    expect(result).toEqual({ created: ['review'], failed: ['implementer'] });
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('implementer'), expect.any(Error));
    warn.mockRestore();
  });
});
