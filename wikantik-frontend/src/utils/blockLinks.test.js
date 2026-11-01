import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { addBlockLinkButtons, findBlockTarget, blockText } from './blockLinks';

const flush = () => new Promise((r) => setTimeout(r, 0));

function mount(html) {
  const root = document.createElement('article');
  root.innerHTML = html;
  document.body.appendChild(root);
  return root;
}

describe('blockLinks', () => {
  let root;
  let clip;
  let cleanup;
  const opts = (over = {}) => ({
    pageName: 'P', canAssign: true, version: 3,
    assign: vi.fn(async () => ({ id: 'new1', version: 4 })),
    onCopy: vi.fn(), onError: vi.fn(), ...over,
  });
  const run = (o) => { cleanup = addBlockLinkButtons(root, o); return o; };

  beforeEach(() => {
    clip = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: clip }, configurable: true });
  });
  afterEach(() => { cleanup?.(); root?.remove(); cleanup = null; });

  it('puts buttons only on eligible blocks', () => {
    root = mount(`<p data-block="0" id="^a">one</p><p data-block="1">two</p>
      <div class="inserted-page"><p data-block="0" id="^b">inc</p></div>
      <div class="wiki-embed"><p data-block="0">emb</p></div>`);
    run(opts());
    expect(root.querySelectorAll('.block-link-btn')).toHaveLength(2);
    expect(root.querySelector('.inserted-page .block-link-btn')).toBeNull();
    expect(root.querySelector('.wiki-embed .block-link-btn')).toBeNull();
  });

  it('skips id-less blocks when assigning is not allowed', () => {
    root = mount('<p data-block="0" id="^a">one</p><p data-block="1">two</p>');
    run(opts({ canAssign: false }));
    expect(root.querySelectorAll('.block-link-btn')).toHaveLength(1);
  });

  it('is idempotent across re-runs and cleanup removes the listener', async () => {
    root = mount('<p data-block="0" id="^a">one</p>');
    const o = run(opts());
    cleanup();
    cleanup = addBlockLinkButtons(root, o);
    expect(root.querySelectorAll('.block-link-btn')).toHaveLength(1);
    root.querySelector('.block-link-btn').click();
    await flush();
    expect(clip).toHaveBeenCalledTimes(1);
    cleanup();
    root.querySelector('.block-link-btn').click();
    await flush();
    expect(clip).toHaveBeenCalledTimes(1);
  });

  it('copies the wikilink for a block that has an id', async () => {
    root = mount('<p data-block="0" id="^abc">one</p>');
    const o = run(opts());
    root.querySelector('.block-link-btn').click();
    await flush();
    expect(clip).toHaveBeenCalledWith('[[P#^abc]]');
    expect(o.onCopy).toHaveBeenCalled();
    expect(o.assign).not.toHaveBeenCalled();
  });

  it('copies the URL from the secondary button', async () => {
    root = mount('<p data-block="0" id="^abc">one</p>');
    run(opts());
    root.querySelector('.block-link-url-btn').click();
    await flush();
    expect(clip).toHaveBeenCalledWith(`${window.location.origin}/wiki/P#^abc`);
  });

  it('assigns an id by ordinal read from data-block (not DOM order), then copies', async () => {
    root = mount('<p id="^z" data-block="0">x</p><p data-block="7">Hello, <b>World</b> 1!</p>');
    const o = run(opts());
    root.querySelectorAll('.block-link-btn')[1].click();
    await flush();
    expect(o.assign).toHaveBeenCalledWith({ version: 3, index: 7, fingerprint: 'helloworld1' });
    expect(root.querySelector('[data-block="7"]').id).toBe('^new1');
    expect(clip).toHaveBeenCalledWith('[[P#^new1]]');
  });

  it('sends the new version on a second assign', async () => {
    root = mount('<p data-block="0">a</p><p data-block="1">b</p>');
    const o = opts();
    o.assign = vi.fn()
      .mockResolvedValueOnce({ id: 'aaa111', version: 4 })
      .mockResolvedValueOnce({ id: 'bbb222', version: 5 });
    run(o);
    const btns = root.querySelectorAll('.block-link-btn');
    btns[0].click(); await flush();
    btns[1].click(); await flush();
    expect(o.assign.mock.calls[1][0].version).toBe(4);
  });

  it('a re-run updates the version used by existing buttons', async () => {
    root = mount('<p data-block="0">a</p>');
    const first = run(opts());
    cleanup();
    const second = run(opts({ version: 9 }));
    root.querySelector('.block-link-btn').click();
    await flush();
    expect(first.assign).not.toHaveBeenCalled();
    expect(second.assign.mock.calls[0][0].version).toBe(9);
  });

  it('ignores a click on an id-less block once assigning is no longer allowed', async () => {
    root = mount('<p data-block="0">a</p>');
    run(opts());
    cleanup();
    const o = run(opts({ canAssign: false }));
    expect(root.querySelector('.block-link-btn')).toBeNull();
    await flush();
    expect(o.assign).not.toHaveBeenCalled();
  });

  it.each([
    ['stale', 'This page changed — reload to copy a block link'],
    ['block-not-located', "Couldn't locate this block — add an id in the editor"],
    ['derived', "This page is generated; block ids can't be added here"],
  ])('maps 409 %s to its message', async (reason, message) => {
    root = mount('<p data-block="0">a</p>');
    const o = opts({ assign: vi.fn(async () => { throw Object.assign(new Error('x'), { status: 409, body: { reason } }); }) });
    run(o);
    root.querySelector('.block-link-btn').click();
    await flush();
    expect(o.onError).toHaveBeenCalledWith(message);
    expect(root.querySelector('p').id).toBe('');
  });

  it('reports other assign failures generically', async () => {
    root = mount('<p data-block="0">a</p>');
    const o = opts({ assign: vi.fn(async () => { throw Object.assign(new Error('boom'), { status: 500 }); }) });
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    run(o);
    root.querySelector('.block-link-btn').click();
    await flush();
    expect(o.onError).toHaveBeenCalledWith("Couldn't copy link");
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('reports an unknown 409 reason generically', async () => {
    root = mount('<p data-block="0">a</p>');
    const o = opts({ assign: vi.fn(async () => { throw Object.assign(new Error('x'), { status: 409, body: {} }); }) });
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    run(o);
    root.querySelector('.block-link-btn').click();
    await flush();
    expect(o.onError).toHaveBeenCalledWith("Couldn't copy link");
  });

  it('reports a clipboard failure', async () => {
    root = mount('<p data-block="0" id="^abc">one</p>');
    clip.mockRejectedValue(new Error('denied'));
    const o = run(opts());
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    root.querySelector('.block-link-btn').click();
    await flush();
    expect(o.onError).toHaveBeenCalledWith("Couldn't copy link");
  });

  it('works without callbacks and ignores unrelated clicks', async () => {
    root = mount('<p data-block="0" id="^abc">one</p>');
    cleanup = addBlockLinkButtons(root, { pageName: 'P' });
    root.querySelector('p').click();
    root.querySelector('.block-link-btn').click();
    await flush();
    expect(clip).toHaveBeenCalled();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    clip.mockRejectedValue(new Error('no'));
    root.querySelector('.block-link-btn').click();
    await flush();
  });

  it('does nothing without a root', () => {
    expect(addBlockLinkButtons(null, {})).toBeTypeOf('function');
  });

  it('fingerprint text excludes KaTeX MathML and our own buttons', () => {
    root = mount('<p data-block="0">E <span class="katex"><span class="katex-mathml">MATHML</span><span class="katex-html">x</span></span></p>');
    run(opts());
    expect(blockText(root.querySelector('p'))).toBe('E x');
  });

  describe('fix round 1', () => {
    const deferred = () => { let resolve; let reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };

    it('keeps the known version monotonic across a re-run with a stale page version', async () => {
      root = mount('<p data-block="0">a</p><p data-block="1">b</p>');
      const o = opts({ version: 3 });
      run(o);
      root.querySelectorAll('.block-link-btn')[0].click(); await flush();
      cleanup();
      run({ ...o, version: 3 });
      root.querySelectorAll('.block-link-btn')[1].click(); await flush();
      expect(o.assign.mock.calls[1][0].version).toBe(4);
    });

    it('starts afresh when the page name changes', async () => {
      root = mount('<p data-block="0">a</p>');
      const o = opts({ version: 9 });
      run(o);
      cleanup();
      const o2 = run(opts({ pageName: 'Q', version: 2 }));
      root.querySelector('.block-link-btn').click(); await flush();
      expect(o2.assign.mock.calls[0][0].version).toBe(2);
    });

    it('repeat clicks share one assignment and mark the block busy', async () => {
      root = mount('<p data-block="0">a</p>');
      const d = deferred();
      const o = opts({ assign: vi.fn(() => d.promise) });
      run(o);
      const p = root.querySelector('p');
      root.querySelector('.block-link-btn').click();
      root.querySelector('.block-link-btn').click();
      root.querySelector('.block-link-url-btn').click();
      expect(p.getAttribute('aria-busy')).toBe('true');
      d.resolve({ id: 'zzz111', version: 4 });
      await flush();
      expect(o.assign).toHaveBeenCalledTimes(1);
      expect(o.onError).not.toHaveBeenCalled();
      expect(p.hasAttribute('aria-busy')).toBe(false);
      expect(o.onCopy).toHaveBeenCalled();
    });

    it('serializes assigns so the second carries the first returned version', async () => {
      root = mount('<p data-block="0">a</p><p data-block="1">b</p>');
      const d1 = deferred();
      const d2 = deferred();
      const o = opts({ version: 3, assign: vi.fn().mockReturnValueOnce(d1.promise).mockReturnValueOnce(d2.promise) });
      run(o);
      const [b0, b1] = root.querySelectorAll('.block-link-btn');
      b0.click(); b1.click();
      await flush();
      expect(o.assign).toHaveBeenCalledTimes(1);
      d1.resolve({ id: 'aaa111', version: 4 });
      await flush();
      expect(o.assign).toHaveBeenCalledTimes(2);
      expect(o.assign.mock.calls[1][0].version).toBe(4);
      d2.resolve({ id: 'bbb222', version: 5 });
      await flush();
      expect(o.onError).not.toHaveBeenCalled();
    });

    it('a failed assign does not block the next one', async () => {
      root = mount('<p data-block="0">a</p><p data-block="1">b</p>');
      vi.spyOn(console, 'warn').mockImplementation(() => {});
      const o = opts({ assign: vi.fn().mockRejectedValueOnce(new Error('x')).mockResolvedValueOnce({ id: 'ok1', version: 4 }) });
      run(o);
      const [b0, b1] = root.querySelectorAll('.block-link-btn');
      b0.click(); b1.click(); await flush();
      expect(o.assign).toHaveBeenCalledTimes(2);
      expect(root.querySelectorAll('p')[1].id).toBe('^ok1');
    });

    it('writes through ClipboardItem when available (inside the gesture)', async () => {
      root = mount('<p data-block="0" id="^abc">one</p>');
      const write = vi.fn(() => Promise.resolve());
      Object.defineProperty(navigator, 'clipboard', { value: { write, writeText: clip }, configurable: true });
      class FakeItem { constructor(data) { this.data = data; } }
      vi.stubGlobal('ClipboardItem', FakeItem);
      try {
        const o = run(opts());
        root.querySelector('.block-link-btn').click();
        await flush();
        expect(write).toHaveBeenCalledTimes(1);
        expect(clip).not.toHaveBeenCalled();
        const blob = await write.mock.calls[0][0][0].data['text/plain'];
        expect(await blob.text()).toBe('[[P#^abc]]');
        expect(o.onCopy).toHaveBeenCalled();
      } finally { vi.unstubAllGlobals(); }
    });

    it('reports a failed ClipboardItem write', async () => {
      root = mount('<p data-block="0" id="^abc">one</p>');
      Object.defineProperty(navigator, 'clipboard', { value: { write: vi.fn(() => Promise.reject(new Error('no'))), writeText: clip }, configurable: true });
      vi.stubGlobal('ClipboardItem', class { constructor(d) { this.d = d; } });
      vi.spyOn(console, 'warn').mockImplementation(() => {});
      try {
        const o = run(opts());
        root.querySelector('.block-link-btn').click();
        await flush();
        expect(o.onError).toHaveBeenCalledWith("Couldn't copy link");
      } finally { vi.unstubAllGlobals(); }
    });

    it('removes buttons from id-less blocks when assigning stops being allowed', () => {
      root = mount('<p data-block="0" id="^a">one</p><p data-block="1">two</p>');
      const o = run(opts());
      expect(root.querySelectorAll('.block-link-btn')).toHaveLength(2);
      cleanup();
      run({ ...o, canAssign: false });
      expect(root.querySelectorAll('.block-link-btn')).toHaveLength(1);
      expect(root.querySelector('[data-block="1"]').dataset.blocklinkInjected).toBeUndefined();
    });

    it('adds no text nodes to a block', () => {
      root = mount('<p data-block="0">hello</p>');
      run(opts());
      expect(root.querySelector('p').textContent).toBe('hello');
      expect(root.querySelector('.block-link-btn').textContent).toBe('');
    });

    it('has one tab stop per block and arrow keys move between the two buttons', () => {
      root = mount('<p data-block="0" id="^a">one</p>');
      run(opts());
      const main = root.querySelector('.block-link-btn');
      const url = root.querySelector('.block-link-url-btn');
      expect(url.getAttribute('tabindex')).toBe('-1');
      expect(main.hasAttribute('tabindex')).toBe(false);
      main.focus();
      main.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
      expect(document.activeElement).toBe(url);
      url.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
      expect(document.activeElement).toBe(main);
      main.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
      expect(document.activeElement).toBe(main);
      root.querySelector('p').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    });

    it('excludes drawn fence chrome from the fingerprint text', () => {
      root = mount('<figure data-block="0"><div class="wk-fence-drawing"><svg><text>DRAWN</text></svg></div><div class="wk-fence-toolbar">Copy</div><pre>src</pre></figure>');
      expect(blockText(root.querySelector('figure'))).toBe('src');
    });
  });

  describe('findBlockTarget', () => {
    it('prefers the page own element over an earlier included copy', () => {
      root = mount(`<div class="inserted-page"><p id="^abc">inc</p></div><p id="^abc" data-block="0">own</p>`);
      expect(findBlockTarget(root, '^abc').textContent).toBe('own');
    });
    it('falls back to an embed wrapper carrying the id', () => {
      root = mount('<div class="wiki-embed" id="^e1"><p>body</p></div>');
      expect(findBlockTarget(root, '^e1').className).toBe('wiki-embed');
    });
    it('does not land inside an included page when there is no own element', () => {
      root = mount('<div class="inserted-page"><p id="^abc">inc</p></div>');
      expect(findBlockTarget(root, '^abc')).toBeNull();
    });
    it('returns null for a missing id', () => {
      root = mount('<p id="^a">x</p>');
      expect(findBlockTarget(root, '^nope')).toBeNull();
    });
  });
});
