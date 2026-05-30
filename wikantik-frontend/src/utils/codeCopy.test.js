import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { addCopyButtons } from './codeCopy';

describe('addCopyButtons', () => {
  let container;

  beforeEach(() => {
    container = document.createElement('div');
    container.innerHTML = '<pre><code>hello world</code></pre>';
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('injects exactly one .code-copy-btn into a pre block', () => {
    addCopyButtons(container, { onCopy: vi.fn(), onError: vi.fn() });
    const btns = container.querySelectorAll('.code-copy-btn');
    expect(btns.length).toBe(1);
  });

  it('is idempotent — running twice does not duplicate buttons', () => {
    const onCopy = vi.fn();
    addCopyButtons(container, { onCopy, onError: vi.fn() });
    addCopyButtons(container, { onCopy, onError: vi.fn() });
    const btns = container.querySelectorAll('.code-copy-btn');
    expect(btns.length).toBe(1);
  });

  it('handles multiple pre blocks', () => {
    container.innerHTML = '<pre><code>a</code></pre><pre><code>b</code></pre>';
    addCopyButtons(container, { onCopy: vi.fn(), onError: vi.fn() });
    expect(container.querySelectorAll('.code-copy-btn').length).toBe(2);
  });

  it('clicking the button calls navigator.clipboard.writeText with code text', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } });
    const onCopy = vi.fn();
    addCopyButtons(container, { onCopy, onError: vi.fn() });
    const btn = container.querySelector('.code-copy-btn');
    btn.click();
    await Promise.resolve(); // flush microtask
    await Promise.resolve();
    expect(writeText).toHaveBeenCalledWith('hello world');
  });

  it('clicking the button calls onCopy callback on success', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } });
    const onCopy = vi.fn();
    addCopyButtons(container, { onCopy, onError: vi.fn() });
    container.querySelector('.code-copy-btn').click();
    // Flush the promise chain: writeText resolves, then the .then() callback fires.
    await new Promise((r) => setTimeout(r, 0));
    expect(onCopy).toHaveBeenCalled();
  });

  it('calls onError callback when clipboard rejects', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('denied'));
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } });
    const onError = vi.fn();
    addCopyButtons(container, { onCopy: vi.fn(), onError });
    container.querySelector('.code-copy-btn').click();
    await new Promise((r) => setTimeout(r, 0));
    expect(onError).toHaveBeenCalled();
  });
});
