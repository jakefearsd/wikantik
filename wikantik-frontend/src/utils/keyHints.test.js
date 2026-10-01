import { describe, it, expect } from 'vitest';
import { formatKeys, isMacPlatform } from './keyHints';

describe('formatKeys', () => {
  it.each([
    ['Mod-Shift-p', 'Ctrl+Shift+P', '⌘⇧P'],
    ['Mod-O', 'Ctrl+O', '⌘O'],
    ['Mod-s', 'Ctrl+S', '⌘S'],
    ['Ctrl-Alt-[', 'Ctrl+Alt+[', '⌃⌥['],
    ['Ctrl-Alt-]', 'Ctrl+Alt+]', '⌃⌥]'],
    ['Alt-Enter', 'Alt+Enter', '⌥Enter'],
    ['Mod--', 'Ctrl+-', '⌘-'],
  ])('%s → %s (PC) / %s (Mac)', (keys, pc, mac) => {
    expect(formatKeys(keys, { mac: false })).toBe(pc);
    expect(formatKeys(keys, { mac: true })).toBe(mac);
  });

  it('returns an empty string for no binding', () => {
    expect(formatKeys(undefined, { mac: false })).toBe('');
    expect(formatKeys('', { mac: true })).toBe('');
  });

  it('detects the platform when not told', () => {
    expect(formatKeys('Mod-K')).toBe(isMacPlatform() ? '⌘K' : 'Ctrl+K');
  });
});

describe('isMacPlatform', () => {
  it.each([
    [{ platform: 'MacIntel' }, true],
    [{ platform: 'iPhone' }, true],
    [{ userAgentData: { platform: 'macOS' }, platform: '' }, true],
    [{ userAgentData: { platform: 'Windows' }, platform: 'MacIntel' }, false],
    [{ platform: 'Win32' }, false],
    [{ platform: 'Linux x86_64' }, false],
    [{}, false],
    [undefined, false],
  ])('%j → %s', (nav, expected) => expect(isMacPlatform(nav)).toBe(expected));
});
