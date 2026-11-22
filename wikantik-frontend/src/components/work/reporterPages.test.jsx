// A reporter must not learn anything about a project beyond what the intake routes return. This renders the
// reporter-facing pages against a recording HTTP client and fails on any request outside the allowed routes.
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

const net = vi.hoisted(() => ({ calls: [], respond: null }));
vi.mock('../../api/client', () => ({
  apiUrl: (p) => p,
  collabRequest: vi.fn((url, opts = {}) => {
    net.calls.push(`${opts.method || 'GET'} ${url}`);
    return net.respond(url, opts);
  }),
  multipartRequest: vi.fn((url) => { net.calls.push(`POST ${url}`); return Promise.resolve({ id: 1 }); }),
  api: {},
}));
vi.mock('../../hooks/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }) }));
vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ user: { authenticated: true, roles: [] }, loading: false }) }));
vi.mock('../../hooks/useCapabilities', () => ({ useCapabilities: () => ({ capabilities: { work: true }, loading: false }) }));
import WorkHome from './WorkHome';
import WorkItemView from './WorkItemView';
import WorkSubmit from './WorkSubmit';

/** Every route a reporter-facing page may call (method + path, the /api/work prefix removed). */
const ALLOWED = [
  /^GET \/api\/work\/me$/,
  /^GET \/api\/work\/intake\/projects$/,
  /^POST \/api\/work\/intake\/items$/,
  /^GET \/api\/work\/intake\/items$/,
  /^GET \/api\/work\/intake\/items\/[A-Z0-9-]+$/,
  /^POST \/api\/work\/intake\/items\/[A-Z0-9-]+\/comments$/,
  /^GET \/api\/work\/inbox(\?.*)?$/,
  /^GET \/api\/work\/inbox\/unread$/,
  /^POST \/api\/work\/inbox\/read\/[A-Z0-9-]+$/,
  /^POST \/api\/work\/inbox\/read-all$/,
  /^(GET|PUT) \/api\/work\/watch\/[A-Z0-9-]+$/,
  /^GET \/api\/work\/items\/[A-Z0-9-]+\/attachments$/,
  /^POST \/api\/work\/items\/[A-Z0-9-]+\/attachments$/,
  /^DELETE \/api\/work\/items\/[A-Z0-9-]+\/attachments\/\d+$/,
];
const outside = () => net.calls.filter((c) => !ALLOWED.some((re) => re.test(c)));

const REPORTED = {
  key: 'PLAT-4', projectKey: 'PLAT', projectName: 'Platform', level: 'story', type: 'bug', status: 'ready', title: 'Mine',
  body: 'x', bodyHtml: '<p>x</p>', reporter: 'rita', reporterName: 'Rita R', createdAt: '2026-11-01T10:00:00Z',
  updatedAt: '2026-11-01T10:00:00Z', comments: [], events: [{ id: 1, type: 'created', actorName: 'Rita R', at: '2026-11-01T10:00:00Z' }], archived: false,
};
const notFound = () => Promise.reject(Object.assign(new Error('Not found.'), { status: 404 }));

beforeEach(() => {
  net.calls.length = 0;
  net.respond = (url, opts) => {
    if (url === '/api/work/me') return Promise.resolve({ projects: [], waitingOn: [], reportable: [{ key: 'PLAT', name: 'Platform', role: 'reporter' }] });
    if (url === '/api/work/intake/projects') return Promise.resolve([{ key: 'PLAT', name: 'Platform', role: 'reporter' }]);
    if (url === '/api/work/intake/items' && opts.method === 'POST') return Promise.resolve({ key: 'PLAT-4' });
    if (url === '/api/work/intake/items') return Promise.resolve([{ key: 'PLAT-4', projectKey: 'PLAT', projectName: 'Platform', title: 'Mine', status: 'ready' }]);
    if (url === '/api/work/intake/items/PLAT-4') return Promise.resolve(REPORTED);
    if (url.startsWith('/api/work/intake/items/PLAT-4/comments')) return Promise.resolve({ id: 9 });
    if (url === '/api/work/inbox/unread') return Promise.resolve({ unread: 0 });
    if (url.startsWith('/api/work/watch/')) return Promise.resolve({ state: 'watching' });
    if (url.endsWith('/attachments')) return Promise.resolve({ attachments: [], mayAttach: true, maxFileBytes: 1e7, maxItemBytes: 5e7, usedBytes: 0 });
    if (url.startsWith('/api/work/inbox/read')) return Promise.resolve({});
    if (url.startsWith('/api/work/items/PLAT-4')) return notFound();
    return Promise.reject(new Error(`unexpected ${url}`));
  };
});

const at = (path, ui) => render(
  <MemoryRouter initialEntries={[path]}>
    <Routes>
      <Route path="/work" element={ui} />
      <Route path="/work/submit" element={ui} />
      <Route path="/work/item/:key" element={ui} />
    </Routes>
  </MemoryRouter>);

describe('reporter pages call only the allowed routes', () => {
  it('the Work home of a reporter with no projects', async () => {
    at('/work', <WorkHome />);
    await screen.findByRole('link', { name: 'File a request' });
    await screen.findByRole('link', { name: /PLAT-4 Mine/ });
    expect(outside()).toEqual([]);
    expect(net.calls).toContain('GET /api/work/intake/items');
  });

  it('the submit page, filing with a file', async () => {
    at('/work/submit', <WorkSubmit />);
    await screen.findByRole('option', { name: 'Platform' });
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Broken' } });
    fireEvent.change(screen.getByLabelText('Files'), { target: { files: [new File(['x'], 'a.txt')] } });
    fireEvent.click(screen.getByRole('button', { name: 'File request' }));
    await waitFor(() => expect(net.calls).toContain('POST /api/work/items/PLAT-4/attachments'));
    expect(outside()).toEqual([]);
  });

  it('the report view, reading, watching state, attachments and commenting', async () => {
    at('/work/item/PLAT-4', <WorkItemView />);
    await screen.findByRole('heading', { name: 'Mine' });
    fireEvent.change(screen.getByLabelText('Add a comment'), { target: { value: '@a hello' } });
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    await waitFor(() => expect(net.calls).toContain('POST /api/work/intake/items/PLAT-4/comments'));
    await waitFor(() => expect(net.calls).toContain('GET /api/work/watch/PLAT-4'));
    await waitFor(() => expect(net.calls).toContain('GET /api/work/items/PLAT-4/attachments'));
    // the only request outside the allowed list is the full item view the page tries first: a 404 for a reporter
    expect(outside()).toEqual(['GET /api/work/items/PLAT-4']);
    expect(net.calls.some((c) => /members|labels|sprints|board|activity|products|filters|flow|queues|events|revisions|changes/.test(c))).toBe(false);
  });
});
