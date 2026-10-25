import { describe, it, expect, vi } from 'vitest';

vi.mock('./mermaidRenderer.js', () => ({ draw: vi.fn() }));

import { FENCE_RENDERERS, rendererFor, rendererForLanguage } from './registry';
import { parseFenceInfo } from './fenceInfo';
import { checkMermaid } from './mermaidPolicy';

describe('fence renderer registry', () => {
  it('registers Mermaid with the shared policy and a lazy loader', async () => {
    expect(FENCE_RENDERERS.mermaid.check).toBe(checkMermaid);
    const mod = await FENCE_RENDERERS.mermaid.load();
    expect(typeof mod.draw).toBe('function');
  });

  it('registers PlantUML as a server kind that the client never gates', async () => {
    expect(FENCE_RENDERERS.mermaid.kind).toBe('browser');
    expect(FENCE_RENDERERS.plantuml.kind).toBe('server');
    expect(FENCE_RENDERERS.plantuml.check('anything')).toEqual({ allowed: true });
    expect(rendererFor(parseFenceInfo('plantuml'))).toBe(FENCE_RENDERERS.plantuml);
    expect(rendererFor(parseFenceInfo('plantuml example'))).toBeNull();
  });

  it('dispatches a parsed info string', () => {
    expect(rendererFor(parseFenceInfo('mermaid'))).toBe(FENCE_RENDERERS.mermaid);
    expect(rendererFor(parseFenceInfo('Mermaid title=x'))).toBe(FENCE_RENDERERS.mermaid);
  });

  it('returns null for example fences, unregistered languages and missing input', () => {
    expect(rendererFor(parseFenceInfo('mermaid example'))).toBeNull();
    expect(rendererFor(parseFenceInfo('java'))).toBeNull();
    expect(rendererFor(parseFenceInfo(''))).toBeNull();
    expect(rendererFor(null)).toBeNull();
    expect(rendererFor(undefined)).toBeNull();
  });

  it('never matches inherited properties', () => {
    expect(rendererFor(parseFenceInfo('constructor'))).toBeNull();
    expect(rendererForLanguage('__proto__')).toBeNull();
    expect(rendererForLanguage('toString')).toBeNull();
    expect(rendererForLanguage(undefined)).toBeNull();
  });
});
