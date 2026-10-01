import { describe, it, expect } from 'vitest';
import { clusterLabel } from './clusterLabel';

describe('clusterLabel', () => {
  it('title-cases a kebab slug', () => {
    expect(clusterLabel('index-fund-investing')).toBe('Index Fund Investing');
  });
  it('joins sub-cluster segments with a chevron', () => {
    expect(clusterLabel('parent/child')).toBe('Parent › Child');
    expect(clusterLabel('personal-finance/index-funds')).toBe('Personal Finance › Index Funds');
  });
  it('treats underscores and stray whitespace as word breaks', () => {
    expect(clusterLabel('  machine_learning ')).toBe('Machine Learning');
  });
  it('keeps the rest of each word as written', () => {
    expect(clusterLabel('llm-RAG')).toBe('Llm RAG');
  });
  it('labels each entry of a multi-membership list', () => {
    expect(clusterLabel(['a-b', 'c/d'])).toBe('A B, C › D');
  });
  it('is empty for blank or missing input', () => {
    expect(clusterLabel('')).toBe('');
    expect(clusterLabel('   ')).toBe('');
    expect(clusterLabel(null)).toBe('');
    expect(clusterLabel(undefined)).toBe('');
    expect(clusterLabel([])).toBe('');
  });
});
