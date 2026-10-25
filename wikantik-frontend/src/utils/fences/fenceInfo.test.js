import { describe, it, expect } from 'vitest';
import fixture from '../__fixtures__/renderedFences.json';
import { parseFenceInfo } from './fenceInfo';

describe('parseFenceInfo', () => {
  it('has the shared cases', () => {
    expect(fixture.infoStrings.length).toBeGreaterThanOrEqual(10);
  });

  it.each(fixture.infoStrings.map((c) => [JSON.stringify(c.info), c]))('matches the shared fixture: %s', (_n, c) => {
    expect(parseFenceInfo(c.info)).toEqual({ language: c.language, example: c.example, modifiers: c.modifiers });
  });

  it('treats undefined like null', () => {
    expect(parseFenceInfo(undefined)).toEqual({ language: '', example: false, modifiers: [] });
  });

  it('trims ASCII control characters but splits only on ASCII whitespace (Java String.trim / \\s parity)', () => {
    expect(parseFenceInfo('\u0001mermaid\u0001')).toEqual({ language: 'mermaid', example: false, modifiers: [] });
    expect(parseFenceInfo('mermaid\u0001example')).toEqual({ language: 'mermaid\u0001example', example: false, modifiers: [] });
    expect(parseFenceInfo('mermaid\u000bexample')).toEqual({ language: 'mermaid', example: true, modifiers: ['example'] });
    expect(parseFenceInfo('\u3000mermaid')).toEqual({ language: '\u3000mermaid', example: false, modifiers: [] });
  });
});
