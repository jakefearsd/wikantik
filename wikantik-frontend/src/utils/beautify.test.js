import { describe, it, expect } from 'vitest';
import { beautify } from './beautify';

// Mirrors TextUtilTest's beautifyString cases so the client and server split names identically.
describe('beautify', () => {
  it.each([
    ['CamelCase', 'Camel Case'],
    ['LowCostIndexFundInvesting', 'Low Cost Index Fund Investing'],
    ['Page123Test', 'Page 123 Test'],
    ['HTMLParser', 'HTML Parser'],
    ['Test3rd', 'Test 3 rd'],
    ['X', 'X'],
    ['hello', 'hello'],
    ['', ''],
    [null, ''],
    [undefined, ''],
  ])('%s → %s', (name, expected) => expect(beautify(name)).toBe(expected));
});
