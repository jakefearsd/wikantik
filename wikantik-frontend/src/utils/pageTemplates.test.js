import { describe, it, expect } from 'vitest';
import { fillTemplate, buildInitialPage } from './pageTemplates';

describe('pageTemplates', () => {
  it('fills title and date placeholders everywhere', () => {
    expect(fillTemplate('# {{title}}\n{{date}} {{title}}', { title: 'T', date: '2026-09-30' }))
      .toBe('# T\n2026-09-30 T');
  });

  it('builds metadata from the template plus date and cluster', () => {
    const template = { type: 'runbook', metadata: { type: 'runbook', status: 'active', runbook: { steps: ['a', 'b'] } },
                       body: '# {{title}}\n\n## Notes\n' };
    const { initialMetadata, initialContent } = buildInitialPage({
      template, title: 'Restart X', type: 'runbook', cluster: 'ops', today: '2026-09-30' });
    expect(initialMetadata).toEqual({ type: 'runbook', status: 'active', runbook: { steps: ['a', 'b'] },
                                      date: '2026-09-30', cluster: 'ops' });
    expect(initialContent).toBe('# Restart X\n\n## Notes\n');
  });

  it('falls back to a bare title page without a template and omits a blank cluster', () => {
    const { initialMetadata, initialContent } = buildInitialPage({
      template: null, title: 'Plain', type: 'article', cluster: '  ', today: '2026-09-30' });
    expect(initialMetadata).toEqual({ type: 'article', status: 'active', date: '2026-09-30' });
    expect(initialContent).toBe('# Plain\n\n');
  });

  it('does not let the template mutate between uses', () => {
    const template = { type: 'article', metadata: { type: 'article', status: 'active' }, body: '# {{title}}\n\n' };
    buildInitialPage({ template, title: 'A', type: 'article', cluster: 'c', today: 'd' });
    expect(template.metadata).toEqual({ type: 'article', status: 'active' });
  });
});
