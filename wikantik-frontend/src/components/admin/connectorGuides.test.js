import { describe, it, expect } from 'vitest';
import { CONNECTOR_TYPES, TYPE_ORDER } from './connectorGuides';

describe('connectorGuides', () => {
  it('covers exactly the six UI types in order', () => {
    expect(TYPE_ORDER).toEqual(['webcrawler', 'sitemap', 'feed', 'github', 'confluence', 'gdrive']);
    expect(Object.keys(CONNECTOR_TYPES).sort()).toEqual([...TYPE_ORDER].sort());
  });

  it('every type has label, icon, blurb, goodFor, fields, expectations; secret types have authGuide steps', () => {
    for (const t of TYPE_ORDER) {
      const d = CONNECTOR_TYPES[t];
      expect(d.label).toBeTruthy();
      expect(d.icon).toBeTruthy();
      expect(d.blurb).toBeTruthy();
      expect(d.goodFor).toBeTruthy();
      expect(Array.isArray(d.fields)).toBe(true);
      expect(d.fields.length).toBeGreaterThan(0);
      expect(d.expectations.length).toBeGreaterThan(80);
      if (d.secrets.length) {
        expect(d.authGuide).toBeTruthy();
        expect(d.authGuide.steps).toBeTruthy();
        expect(d.authGuide.steps.length).toBeGreaterThanOrEqual(3);
      }
    }
  });

  it('required fields are flagged', () => {
    expect(CONNECTOR_TYPES.github.fields.find(f => f.name === 'repo').required).toBe(true);
    expect(CONNECTOR_TYPES.webcrawler.fields.find(f => f.name === 'seeds').required).toBe(true);
    expect(CONNECTOR_TYPES.sitemap.fields.find(f => f.name === 'sitemap_urls').required).toBe(true);
    expect(CONNECTOR_TYPES.feed.fields.find(f => f.name === 'feed_urls').required).toBe(true);
    expect(CONNECTOR_TYPES.confluence.fields.find(f => f.name === 'space_key').required).toBe(true);
    expect(CONNECTOR_TYPES.confluence.fields.find(f => f.name === 'base_url').required).toBe(true);
    expect(CONNECTOR_TYPES.confluence.fields.find(f => f.name === 'email').required).toBe(true);
    expect(CONNECTOR_TYPES.gdrive.fields.find(f => f.name === 'folder_ids').required).toBe(true);
    expect(CONNECTOR_TYPES.gdrive.fields.find(f => f.name === 'client_id').required).toBe(true);
    // redirect_uri is deliberately optional — the server fills in the wiki's own
    // callback URL when it is blank.
    expect(CONNECTOR_TYPES.gdrive.fields.find(f => f.name === 'redirect_uri').required).toBeUndefined();
  });

  it('github authGuide carries the public-repo optionalNote', () => {
    expect(typeof CONNECTOR_TYPES.github.authGuide.optionalNote).toBe('string');
    expect(CONNECTOR_TYPES.github.authGuide.optionalNote.length).toBeGreaterThan(0);
  });

  it('field types are exactly: text | number | bool | list', () => {
    const validTypes = new Set(['text', 'number', 'bool', 'list']);
    for (const t of TYPE_ORDER) {
      const d = CONNECTOR_TYPES[t];
      for (const f of d.fields) {
        expect(validTypes.has(f.type)).toBe(true);
      }
    }
  });

  it('field names match backend codec exactly', () => {
    const webcrawlerFields = CONNECTOR_TYPES.webcrawler.fields.map(f => f.name);
    expect(webcrawlerFields).toContain('seeds');
    expect(webcrawlerFields).toContain('path_prefix');
    expect(webcrawlerFields).toContain('max_pages');
    expect(webcrawlerFields).toContain('max_depth');
    expect(webcrawlerFields).toContain('delay_ms');
    expect(webcrawlerFields).toContain('respect_robots');
    expect(webcrawlerFields).toContain('same_host_only');

    const githubFields = CONNECTOR_TYPES.github.fields.map(f => f.name);
    expect(githubFields).toContain('repo');
    expect(githubFields).toContain('branch');
    expect(githubFields).toContain('path_prefix');
    expect(githubFields).toContain('max_files');

    const confluenceFields = CONNECTOR_TYPES.confluence.fields.map(f => f.name);
    expect(confluenceFields).toContain('base_url');
    expect(confluenceFields).toContain('space_key');
    expect(confluenceFields).toContain('email');
    expect(confluenceFields).toContain('max_pages');

    const gdriveFields = CONNECTOR_TYPES.gdrive.fields.map(f => f.name);
    expect(gdriveFields).toContain('folder_ids');
    expect(gdriveFields).toContain('max_files');
    expect(gdriveFields).toContain('export_mime');
    expect(gdriveFields).toContain('redirect_uri');
  });

  it('secrets match authGuide secretName', () => {
    if (CONNECTOR_TYPES.github.secrets.length > 0) {
      expect(CONNECTOR_TYPES.github.secrets).toContain(CONNECTOR_TYPES.github.authGuide.secretName);
    }
    if (CONNECTOR_TYPES.confluence.secrets.length > 0) {
      expect(CONNECTOR_TYPES.confluence.secrets).toContain(CONNECTOR_TYPES.confluence.authGuide.secretName);
    }
    if (CONNECTOR_TYPES.gdrive.secrets.length > 0) {
      const gdriveSecrets = CONNECTOR_TYPES.gdrive.secrets;
      expect(gdriveSecrets).toContain(CONNECTOR_TYPES.gdrive.authGuide.secretName);
    }
  });

  it('crawlers have no authGuide', () => {
    expect(CONNECTOR_TYPES.webcrawler.authGuide).toBeNull();
    expect(CONNECTOR_TYPES.sitemap.authGuide).toBeNull();
    expect(CONNECTOR_TYPES.feed.authGuide).toBeNull();
  });
});
