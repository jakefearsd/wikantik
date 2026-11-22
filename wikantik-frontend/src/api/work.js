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
/** { events: [{ at, actor, field, from, to }] }: a product's recorded setting changes, newest first (admins only). */
export const productHistory = (key) => request(`${base}/products/${enc(key)}/history`);
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

// Deployments: a product version going to an environment, carrying items. The caller is shown only the carried
// items it can see. A 409 carries body.reason (bad_transition); a refusal's reason is the Error message.
export const listDeployments = (productKey, limit) => request(`${base}/deployments${query({ product: productKey, limit })}`);
export const getDeployment = (id) => request(`${base}/deployments/${enc(id)}`);
export const getDeploymentHistory = (id) => request(`${base}/deployments/${enc(id)}/history`);
/** body is { product, version, items: [itemKey], environment? }. */
export const requestDeployment = (body) => request(`${base}/deployments`, json('POST', body));
export const approveDeployment = (id, body) => request(`${base}/deployments/${enc(id)}/approve`, json('POST', body));
/** Withdraws a deployment you requested, or turns one down you could approve; not once it is running. */
export const rejectDeployment = (id, body) => request(`${base}/deployments/${enc(id)}/reject`, json('POST', body));
export const startDeployment = (id) => request(`${base}/deployments/${enc(id)}/start`, { method: 'POST' });
/** body is { evidence }: the health and smoke-test output; required. */
export const verifyDeployment = (id, body) => request(`${base}/deployments/${enc(id)}/verify`, json('POST', body));
export const failDeployment = (id, body) => request(`${base}/deployments/${enc(id)}/fail`, json('POST', body));
export const rollBackDeployment = (id, body) => request(`${base}/deployments/${enc(id)}/roll-back`, json('POST', body));
/** { shippedIn: [{ id, product, version, environment, verifiedAt }] }: the verified deployments that carried an item. */
export const getShippedIn = (key) => request(`${base}/items/${enc(key)}/shipped-in`);
export const listLabels = (key) => request(`${base}/projects/${enc(key)}/labels`);

// Sprints: a time-boxed plan of items. A refusal's reason is the Error message; a 409 carries body.reason
// (stale, sprint_active, sprint_closed, bad_transition, bad_target, archived) and a 400 body.field.
export const listSprints = (projectKey) => request(`${base}/projects/${enc(projectKey)}/sprints`);
/** body is { name, goal?, startsOn, endsOn } with dates as YYYY-MM-DD. */
export const createSprint = (projectKey, body) => request(`${base}/projects/${enc(projectKey)}/sprints`, json('POST', body));
export const getSprint = (id) => request(`${base}/sprints/${enc(id)}`);
/** body is { version, name?, goal?, startsOn?, endsOn? }; an absent field keeps its value. */
export const updateSprint = (id, body) => request(`${base}/sprints/${enc(id)}`, json('PATCH', body));
export const startSprint = (id, version) => request(`${base}/sprints/${enc(id)}/start`, json('POST', { version }));
/** moveOpenTo is the id of a planned sprint, or null for the backlog. */
export const closeSprint = (id, { version, moveOpenTo }) =>
  request(`${base}/sprints/${enc(id)}/close`, json('POST', { version, moveOpenTo: moveOpenTo ?? null }));
/** { sprint, days: [{ date, remainingPoints, remainingItems, scopePoints, scopeItems }], added, removed }. */
export const getSprintReport = (id) => request(`${base}/sprints/${enc(id)}/report`);
/** { sprints: [{ id, name, endsOn, committedItems, committedPoints, completedItems, completedPoints }] }. */
export const getVelocity = (projectKey) => request(`${base}/projects/${enc(projectKey)}/velocity`);
/** { points }: what an epic or story adds up to. */
export const getItemPoints = (key) => request(`${base}/items/${enc(key)}/points`);

// Notifications: the inbox, watching an item, the email choice, and a project's webhooks.
/** { notices: [{ id, reason, createdAt, readAt, projectKey, itemKey, itemTitle, event }], unread }; `before` is a notice id. */
export const getInbox = (params) => request(`${base}/inbox${query(params)}`);
/** { unread }: how many notices are unread. */
/** A background poll: silent, so a lapsed session never triggers the global login flow from an idle tab. */
export const getUnread = () => request(`${base}/inbox/unread`, { silent: true });
export const markItemRead = (key) => request(`${base}/inbox/read/${enc(key)}`, { method: 'POST' });
export const markAllRead = () => request(`${base}/inbox/read-all`, { method: 'POST' });
/** { state: 'watching' | 'muted' | 'none' }. */
export const getWatch = (key) => request(`${base}/watch/${enc(key)}`);
export const setWatch = (key, state) => request(`${base}/watch/${enc(key)}`, json('PUT', { state }));
/** { email: 'each' | 'daily' | 'off', emailAvailable, webhooksAvailable }. */
export const getNotifyPrefs = () => request(`${base}/notify-prefs`);
export const setNotifyPrefs = (email) => request(`${base}/notify-prefs`, json('PUT', { email }));
/** [{ id, format, target, eventTypes, ownerName, createdAt, lastDeliveredAt, failingSince, lastError, disabledAt, paused }]. */
export const listWebhooks = (projectKey) => request(`${base}/projects/${enc(projectKey)}/webhooks`);
/** body is { url, format, eventTypes }; answers { webhook, secret }, the secret (null for Slack) being shown once. */
export const addWebhook = (projectKey, body) => request(`${base}/projects/${enc(projectKey)}/webhooks`, json('POST', body));
/** body is { url?, eventTypes?, enabled? }; saving a paused webhook makes the saver its owner. */
export const updateWebhook = (projectKey, id, body) =>
  request(`${base}/projects/${enc(projectKey)}/webhooks/${enc(id)}`, json('PATCH', body));
export const removeWebhook = (projectKey, id) =>
  request(`${base}/projects/${enc(projectKey)}/webhooks/${enc(id)}`, { method: 'DELETE' });
