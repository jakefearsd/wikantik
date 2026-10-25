import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('mermaid', () => ({
  default: {
    initialize: vi.fn(),
    render: vi.fn(async (id) => ({ svg: `<svg id="${id}"><g></g></svg>`, bindFunctions: vi.fn() })),
  },
}));

// Fresh module state (the initialised theme) per test.
async function load() {
  vi.resetModules();
  const mermaid = (await import('mermaid')).default;
  const renderer = await import('./mermaidRenderer.js');
  return { mermaid, ...renderer };
}

const EXPECTED_SECURE = ['secure', 'securityLevel', 'startOnLoad', 'maxTextSize', 'suppressErrorRendering',
  'maxEdges', 'dompurifyConfig', 'htmlLabels'];

beforeEach(() => { vi.clearAllMocks(); });

describe('mermaidRenderer.draw', () => {
  it('initialises Mermaid once per theme with the locked-down config', async () => {
    const { mermaid, draw, SECURE_KEYS } = await load();
    expect(SECURE_KEYS).toEqual(EXPECTED_SECURE);
    const host = document.createElement('div');
    await draw(host, 'flowchart TD\n A-->B', { dark: false });
    await draw(host, 'flowchart TD\n A-->B', { dark: false });
    expect(mermaid.initialize).toHaveBeenCalledTimes(1);
    expect(mermaid.initialize).toHaveBeenCalledWith({
      startOnLoad: false, securityLevel: 'antiscript', theme: 'default', suppressErrorRendering: true,
      layout: 'dagre',
      dompurifyConfig: {
        FORBID_TAGS: ['style', 'form', 'input', 'button', 'textarea', 'select', 'option'],
        FORBID_ATTR: ['style'],
      },
      secure: EXPECTED_SECURE,
    });
    await draw(host, 'flowchart TD\n A-->B', { dark: true });
    expect(mermaid.initialize).toHaveBeenCalledTimes(2);
    expect(mermaid.initialize.mock.calls[1][0].theme).toBe('dark');
    await draw(host, 'flowchart TD\n A-->B');
    expect(mermaid.initialize.mock.calls[2][0].theme).toBe('default');
  });

  it('renders under a unique id into the host, as an accessible image, never binding callbacks', async () => {
    const { mermaid, draw } = await load();
    const results = [];
    mermaid.render.mockImplementation(async (id) => {
      const r = { svg: `<svg id="${id}"><g></g></svg>`, bindFunctions: vi.fn() };
      results.push(r);
      return r;
    });
    const a = document.createElement('div');
    const b = document.createElement('div');
    await draw(a, 'flowchart TD\n A-->B');
    await draw(b, 'flowchart TD\n A-->B');
    const [idA, srcA] = mermaid.render.mock.calls[0];
    const [idB] = mermaid.render.mock.calls[1];
    expect(srcA).toBe('flowchart TD\n A-->B');
    expect(idA).not.toBe(idB);
    const svg = a.querySelector('svg');
    expect(svg.id).toBe(idA);
    expect(svg.getAttribute('role')).toBe('img');
    expect(svg.getAttribute('aria-label')).toBe('Diagram');
    results.forEach((r) => expect(r.bindFunctions).not.toHaveBeenCalled());
  });

  it('leaves role="img" off a drawing that holds links, so screen readers still reach them', async () => {
    const { mermaid, draw } = await load();
    mermaid.render.mockResolvedValueOnce({
      svg: '<svg role="graphics-document document" aria-roledescription="flowchart-v2">'
        + '<g><a href="/wiki/Target"><text>T</text></a></g></svg>',
    });
    const host = document.createElement('div');
    await draw(host, 'flowchart TD\n A-->B\n click A "/wiki/Target"');
    const svg = host.querySelector('svg');
    // Mermaid's own role stays: unlike role="img" it does not make the links presentational.
    expect(svg.getAttribute('role')).toBe('graphics-document document');
    expect(svg.getAttribute('aria-label')).toBe('Diagram');
    expect(svg.getAttribute('aria-roledescription')).toBe('flowchart-v2');
  });

  it('labels the drawing from its accTitle', async () => {
    const { mermaid, draw } = await load();
    mermaid.render.mockResolvedValueOnce({ svg: '<svg><title> Order flow </title><g><title>node tip</title></g></svg>' });
    const host = document.createElement('div');
    await draw(host, 'flowchart TD\n accTitle: Order flow\n A-->B');
    expect(host.querySelector('svg').getAttribute('aria-label')).toBe('Order flow');
  });

  it('throws on a parse error, writes nothing, and keeps serving later draws', async () => {
    const { mermaid, draw } = await load();
    mermaid.render.mockRejectedValueOnce(new Error('Parse error on line 2:'));
    const host = document.createElement('div');
    host.textContent = 'before';
    await expect(draw(host, 'flowchart TD\n A--')).rejects.toThrow('Parse error on line 2:');
    expect(host.textContent).toBe('before');
    await draw(host, 'flowchart TD\n A-->B');
    expect(host.querySelector('svg')).not.toBeNull();
  });

  it('copes with a render result that holds no svg element', async () => {
    const { mermaid, draw } = await load();
    mermaid.render.mockResolvedValueOnce({ svg: '' });
    const host = document.createElement('div');
    await draw(host, 'x');
    expect(host.innerHTML).toBe('');
  });

  it('runs draws one at a time, so each renders with its own theme', async () => {
    const { mermaid, draw } = await load();
    const order = [];
    mermaid.initialize.mockImplementation((cfg) => order.push(`init:${cfg.theme}`));
    let releaseFirst;
    mermaid.render
      .mockImplementationOnce(() => new Promise((resolve) => {
        order.push('render:1');
        releaseFirst = () => resolve({ svg: '<svg></svg>' });
      }))
      .mockImplementationOnce(async () => { order.push('render:2'); return { svg: '<svg></svg>' }; });
    const first = draw(document.createElement('div'), 'a', { dark: false });
    const second = draw(document.createElement('div'), 'b', { dark: true });
    await vi.waitFor(() => expect(order).toContain('render:1'));
    expect(order).toEqual(['init:default', 'render:1']);
    releaseFirst();
    await Promise.all([first, second]);
    expect(order).toEqual(['init:default', 'render:1', 'init:dark', 'render:2']);
  });
});
