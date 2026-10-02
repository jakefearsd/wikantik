import { describe, it, expect, vi, beforeEach } from 'vitest';
vi.mock('../embedCache', () => ({ loadEmbed: vi.fn() }));
import { loadEmbed } from '../embedCache';
import { loadEmbedState } from './embedSource';

describe('loadEmbedState', () => {
  beforeEach(() => vi.clearAllMocks());
  it('passes target and section to the shared loader and returns its html', async () => {
    loadEmbed.mockResolvedValue({ html: '<p>hi</p>' });
    await expect(loadEmbedState('Other', 'Intro')).resolves.toEqual({ state: 'ok', html: '<p>hi</p>' });
    expect(loadEmbed).toHaveBeenCalledWith('Other', 'Intro');
  });
  it('maps the server missing / restricted flags', async () => {
    loadEmbed.mockResolvedValueOnce({ html: '<p>x</p>', missing: true });
    await expect(loadEmbedState('Other', null)).resolves.toEqual({ state: 'missing' });
    loadEmbed.mockResolvedValueOnce({ html: '', restricted: true });
    await expect(loadEmbedState('Other', null)).resolves.toEqual({ state: 'restricted' });
  });
  it.each([[404, 'missing'], [403, 'restricted'], [500, 'error']])('maps HTTP %i to %s', async (status, state) => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    loadEmbed.mockRejectedValue(Object.assign(new Error('x'), { status }));
    await expect(loadEmbedState('Other', null)).resolves.toEqual({ state });
    if (state === 'error') expect(warn).toHaveBeenCalledWith(expect.stringContaining('[live-preview]'), 'Other', null, 'x');
    warn.mockRestore();
  });
});
