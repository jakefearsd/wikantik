import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const css = readFileSync(resolve(process.cwd(), 'src/styles/article.css'), 'utf8');

describe('task-list styling', () => {
  it('hides the bullet and tucks the checkbox into the gutter for reading view and editor preview', () => {
    for (const scope of ['.article-prose', '.editor-preview']) {
      expect(css).toContain(`${scope} li.task-list-item`);
      expect(css).toContain(`${scope} li.task-list-item > input[type="checkbox"]`);
    }
    expect(css).toContain('list-style-type: none');
    expect(css).toContain('margin: 0 0.35em 0.2em -1.4em');
  });
});
