import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';

vi.mock('../api/client', () => ({
  api: { getCapabilities: vi.fn() },
}));

import { CapabilitiesProvider, useCapabilities } from './useCapabilities';
import { api } from '../api/client';

const DEFAULT_CAPABILITIES = {
  knowledgeGraph: true,
  hybridSearch: true,
  genaiMode: 'full',
  ontology: true,
  connectors: true,
  citations: true,
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe('useCapabilities outside a provider', () => {
  it('does not throw and returns fail-open defaults', () => {
    const { result } = renderHook(() => useCapabilities());
    expect(result.current).toEqual({ capabilities: DEFAULT_CAPABILITIES, loading: false });
  });
});

describe('CapabilitiesProvider', () => {
  const wrapper = ({ children }) => <CapabilitiesProvider>{children}</CapabilitiesProvider>;

  it('starts loading with fail-open defaults before the fetch resolves', () => {
    api.getCapabilities.mockReturnValue(new Promise(() => {})); // never resolves
    const { result } = renderHook(() => useCapabilities(), { wrapper });

    expect(result.current.loading).toBe(true);
    expect(result.current.capabilities).toEqual(DEFAULT_CAPABILITIES);
  });

  it('merges a successful response over the defaults', async () => {
    api.getCapabilities.mockResolvedValue({ knowledgeGraph: false, genaiMode: 'lite' });
    const { result } = renderHook(() => useCapabilities(), { wrapper });

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.capabilities).toEqual({
      ...DEFAULT_CAPABILITIES,
      knowledgeGraph: false,
      genaiMode: 'lite',
    });
  });

  it('keeps the fail-open defaults when the response is falsy', async () => {
    api.getCapabilities.mockResolvedValue(null);
    const { result } = renderHook(() => useCapabilities(), { wrapper });

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.capabilities).toEqual(DEFAULT_CAPABILITIES);
  });

  it('fails open (keeps defaults, stops loading) when the fetch rejects', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.getCapabilities.mockRejectedValue(new Error('network down'));
    const { result } = renderHook(() => useCapabilities(), { wrapper });

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.capabilities).toEqual(DEFAULT_CAPABILITIES);
    expect(warnSpy).toHaveBeenCalled();
    warnSpy.mockRestore();
  });
});
