import assert from 'node:assert';
import { serializeForm } from '../form-helper.mjs';

// 1. normal submission produces a trimmed payload object
{
  const fd = new Map([['name', '  Ada  '], ['email', 'ada@example.com'],
    ['use_case', 'agents'], ['company_url', '']]);
  const out = serializeForm(fd);
  assert.deepStrictEqual(out, { spam: false,
    payload: { name: 'Ada', email: 'ada@example.com', use_case: 'agents' } });
}

// 2. a filled honeypot flags spam and yields no payload
{
  const fd = new Map([['name', 'Bot'], ['email', 'bot@x.com'],
    ['use_case', ''], ['company_url', 'http://spam']]);
  const out = serializeForm(fd);
  assert.strictEqual(out.spam, true);
  assert.strictEqual(out.payload, null);
}

console.log('serializeForm: all assertions passed');
