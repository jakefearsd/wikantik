// Work-management API client: one function per /api/work endpoint. Goes through client.js's request
// helper (session, error envelope, build-version detection). Errors carry { status, body }; the server's
// reason is the Error message. People are named by display name both ways: responses show display names (a login
// reaches only a wiki admin) and requests may name a person by display name.
import { collabRequest as request } from './client';

const enc = encodeURIComponent;
const json = (method, body) => ({ method, body: JSON.stringify(body ?? {}) });

function query(params = {}) {
  const parts = [];
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    parts.push(`${enc(k)}=${enc(Array.isArray(v) ? v.join(',') : v)}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

const base = '/api/work';

// Projects
export const listProjects = () => request(`${base}/projects`);
export const getProject = (key) => request(`${base}/projects/${enc(key)}`);
export const createProject = (project) => request(`${base}/projects`, json('POST', project));
export const updateProject = (key, changes) => request(`${base}/projects/${enc(key)}`, json('PATCH', changes));
export const archiveProject = (key) => request(`${base}/projects/${enc(key)}/archive`, { method: 'POST' });
export const listMembers = (key) => request(`${base}/projects/${enc(key)}/members`);
export const putMember = (key, member) => request(`${base}/projects/${enc(key)}/members`, json('PUT', member));
export const removeMember = (key, { principal, principalKind }) =>
  request(`${base}/projects/${enc(key)}/members`, json('DELETE', { principal, principalKind }));
export const getBoard = (key, filters = {}) => request(`${base}/projects/${enc(key)}/board${query(filters)}`);

// Products
export const listProducts = () => request(`${base}/products`);
export const createProduct = (product) => request(`${base}/products`, json('POST', product));
export const updateProduct = (key, changes) => request(`${base}/products/${enc(key)}`, json('PATCH', changes));

// Items
export const listItems = (params) => request(`${base}/items${query(params)}`);
export const createItem = (draft) => request(`${base}/items`, json('POST', draft));
export const getItem = (key) => request(`${base}/items/${enc(key)}`);

/**
 * PATCH an item. body is { expectedVersion, changes, note }. A stale version rejects with
 * { status: 409, reason: 'stale', current } (current = the server's ItemView); an archived project rejects with
 * { status: 409, reason: 'archived' } and no current; other failures reject with { status, body }.
 */
export function patchItem(key, body) {
  return request(`${base}/items/${enc(key)}`, json('PATCH', body)).catch(staleOrArchived);
}

function staleOrArchived(e) {
  if (e && e.status === 409) {
    const reason = e.body?.reason || 'stale';
    throw Object.assign(new Error(e.message || (reason === 'archived' ? 'This project is archived.'
      : 'Item changed since it was loaded')),
    { status: 409, reason, current: reason === 'stale' ? e.body?.current : undefined, body: e.body });
  }
  throw e;
}

export const listRevisions = (key) => request(`${base}/items/${enc(key)}/revisions`);
export const listEvents = (key) => request(`${base}/items/${enc(key)}/events`);
export const addComment = (key, body) => request(`${base}/items/${enc(key)}/comments`, json('POST', { body }));
export const editComment = (id, body) => request(`${base}/comments/${enc(id)}`, json('PATCH', { body }));
export const removeComment = (id) => request(`${base}/comments/${enc(id)}`, { method: 'DELETE' });
export const addLink = (key, { kind, target }) => request(`${base}/items/${enc(key)}/links`, json('POST', { kind, target }));
export const removeLink = (id) => request(`${base}/links/${enc(id)}`, { method: 'DELETE' });

// Activity and the viewer
export const getActivity = (params) => request(`${base}/activity${query(params)}`);
/** The assignment log: { events } newest first over the caller's projects; params as /api/work/log. */
export const getAssignmentLog = (params) => request(`${base}/log${query(params)}`);
export const getMe = () => request(`${base}/me`);
export const archiveProduct = (key) => request(`${base}/products/${enc(key)}/archive`, { method: 'POST' });

// Queues (writes: instance admins only)
export const listQueues = () => request(`${base}/queues`);
export const createQueue = (queue) => request(`${base}/queues`, json('POST', queue));
export const updateQueue = (key, changes) => request(`${base}/queues/${enc(key)}`, json('PATCH', changes));
export const retireQueue = (key) => request(`${base}/queues/${enc(key)}/retire`, { method: 'POST' });

// Agents. registerAgent and rotateAgentKey resolve { agent, token }: the token is shown once and is the caller's
// to drop; this module keeps no copy of it. The person who registers an agent is its sponsor.
export const listAgents = () => request(`${base}/agents`);
export const registerAgent = (agent) => request(`${base}/agents`, json('POST', agent));
export const updateAgent = (id, changes) => request(`${base}/agents/${enc(id)}`, json('PATCH', changes));
export const rotateAgentKey = (id) => request(`${base}/agents/${enc(id)}/rotate-key`, { method: 'POST' });
// The caller becomes the agent's sponsor (resolves the agent), or gives it up: nobody names someone else.
export const takeOverAgent = (id) => request(`${base}/agents/${enc(id)}/sponsor`, { method: 'PUT' });
export const releaseAgentSponsorship = (id) => request(`${base}/agents/${enc(id)}/sponsor`, { method: 'DELETE' });
export const retireAgent = (id) => request(`${base}/agents/${enc(id)}/retire`, { method: 'POST' });

// Claims
export const listClaimable = (params) => request(`${base}/claimable${query(params)}`);
export const claimItem = (key) => request(`${base}/items/${enc(key)}/claim`, { method: 'POST' });
export const releaseItem = (key, note) =>
  request(`${base}/items/${enc(key)}/release`, note ? json('POST', { note }) : { method: 'POST' });
/** POST a handoff; body is { expectedVersion, status, assignee, queue, note }. Rejects like patchItem on 409. */
export const handoffItem = (key, body) =>
  request(`${base}/items/${enc(key)}/handoff`, json('POST', body)).catch(staleOrArchived);

// Changes: a branch of work on an item, from open to merged. A refusal's reason is the Error message; a 409
// carries body.reason (open_changes, not_approved, not_in_review, change_closed).
export const listChanges = (key) => request(`${base}/items/${enc(key)}/changes`);
/** body is { branch, baseBranch?, headSha?, reviewUrl?, mergedSha? }; mergedSha records a change merged directly. */
export const openChange = (key, body) => request(`${base}/items/${enc(key)}/changes`, json('POST', body));
export const updateChange = (id, body) => request(`${base}/changes/${enc(id)}`, json('PATCH', body));
export const requestReview = (id) => request(`${base}/changes/${enc(id)}/request-review`, { method: 'POST' });
export const reviewChange = (id, body) => request(`${base}/changes/${enc(id)}/review`, json('POST', body));
export const mergeChange = (id, body) => request(`${base}/changes/${enc(id)}/merge`, json('POST', body));
export const abandonChange = (id, body) => request(`${base}/changes/${enc(id)}/abandon`, json('POST', body));
/** { itemKey: { openChanges, inReview } } for the items of a project that have a live change. */
export const getChangeCounts = (projectKey) => request(`${base}/projects/${enc(projectKey)}/change-counts`);

// What governs an item: { effective: { autonomy, allowDirect, doneMeans }, ackRequired, ackWaiting,
// acknowledgedBy, acknowledgedAt }. Acknowledging is a person's act.
export const getItemPolicy = (key) => request(`${base}/items/${enc(key)}/policy`);
export const acknowledgeItem = (key) => request(`${base}/items/${enc(key)}/acknowledge`, { method: 'POST' });
