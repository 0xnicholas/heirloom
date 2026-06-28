import { http, HttpResponse, delay } from 'msw';
import {
  getTypes, saveTypes,
  getRoles, saveRoles,
  getActions, saveActions,
  getSavedQueries, saveSavedQueries,
} from './store';
import { generateMockResults, mockResourceInstances, mockFunctions, mockEvents } from './data';
import type { ResourceType, Role, Action, SavedQuery, QueryDSL } from '@/lib/types';

const LATENCY = 200;

export const handlers = [
  // ============================================================
  // Schema Registry — Resource Types
  // ============================================================

  http.get('/api/types', async () => {
    await delay(LATENCY);
    return HttpResponse.json(getTypes());
  }),

  http.get('/api/types/:name', async ({ params }) => {
    await delay(LATENCY);
    const types = getTypes();
    const type = types.find(t => t.name === params.name);
    if (!type) return new HttpResponse(null, { status: 404 });
    return HttpResponse.json(type);
  }),

  http.post('/api/types', async ({ request }) => {
    await delay(LATENCY);
    const body = await request.json() as ResourceType;
    const types = getTypes();
    types.push(body);
    saveTypes(types);
    return HttpResponse.json(body, { status: 201 });
  }),

  http.put('/api/types/:name', async ({ params, request }) => {
    await delay(LATENCY);
    const body = await request.json() as ResourceType;
    const types = getTypes();
    const idx = types.findIndex(t => t.name === params.name);
    if (idx === -1) return new HttpResponse(null, { status: 404 });
    types[idx] = { ...body, name: params.name as string };
    saveTypes(types);
    return HttpResponse.json(types[idx]);
  }),

  http.delete('/api/types/:name', async ({ params }) => {
    await delay(LATENCY);
    const types = getTypes().filter(t => t.name !== params.name);
    saveTypes(types);
    return new HttpResponse(null, { status: 204 });
  }),

  // ============================================================
  // Query — Execute + Saved Queries
  // ============================================================

  http.post('/api/query/execute', async ({ request }) => {
    await delay(LATENCY);
    const body = await request.json() as QueryDSL;
    return HttpResponse.json(generateMockResults(body));
  }),

  http.get('/api/queries', async () => {
    await delay(LATENCY);
    return HttpResponse.json(getSavedQueries());
  }),

  http.post('/api/queries', async ({ request }) => {
    await delay(LATENCY);
    const body = await request.json() as SavedQuery;
    const queries = getSavedQueries();
    queries.push(body);
    saveSavedQueries(queries);
    return HttpResponse.json(body, { status: 201 });
  }),

  http.delete('/api/queries/:id', async ({ params }) => {
    await delay(LATENCY);
    const queries = getSavedQueries().filter(q => q.id !== params.id);
    saveSavedQueries(queries);
    return new HttpResponse(null, { status: 204 });
  }),

  // ============================================================
  // Security — Roles
  // ============================================================

  http.get('/api/roles', async () => {
    await delay(LATENCY);
    return HttpResponse.json(getRoles());
  }),

  http.get('/api/roles/:name', async ({ params }) => {
    await delay(LATENCY);
    const roles = getRoles();
    const role = roles.find(r => r.name === params.name);
    if (!role) return new HttpResponse(null, { status: 404 });
    return HttpResponse.json(role);
  }),

  http.post('/api/roles', async ({ request }) => {
    await delay(LATENCY);
    const body = await request.json() as Role;
    const roles = getRoles();
    roles.push(body);
    saveRoles(roles);
    return HttpResponse.json(body, { status: 201 });
  }),

  http.put('/api/roles/:name', async ({ params, request }) => {
    await delay(LATENCY);
    const body = await request.json() as Role;
    const roles = getRoles();
    const idx = roles.findIndex(r => r.name === params.name);
    if (idx === -1) return new HttpResponse(null, { status: 404 });
    roles[idx] = { ...body, name: params.name as string };
    saveRoles(roles);
    return HttpResponse.json(roles[idx]);
  }),

  http.delete('/api/roles/:name', async ({ params }) => {
    await delay(LATENCY);
    const roles = getRoles().filter(r => r.name !== params.name);
    saveRoles(roles);
    return new HttpResponse(null, { status: 204 });
  }),

  // ============================================================
  // Security — Actions
  // ============================================================

  http.get('/api/actions', async () => {
    await delay(LATENCY);
    return HttpResponse.json(getActions());
  }),

  http.get('/api/actions/:name', async ({ params }) => {
    await delay(LATENCY);
    const actions = getActions();
    const action = actions.find(a => a.name === params.name);
    if (!action) return new HttpResponse(null, { status: 404 });
    return HttpResponse.json(action);
  }),

  http.post('/api/actions', async ({ request }) => {
    await delay(LATENCY);
    const body = await request.json() as Action;
    const actions = getActions();
    actions.push(body);
    saveActions(actions);
    return HttpResponse.json(body, { status: 201 });
  }),

  http.put('/api/actions/:name', async ({ params, request }) => {
    await delay(LATENCY);
    const body = await request.json() as Action;
    const actions = getActions();
    const idx = actions.findIndex(a => a.name === params.name);
    if (idx === -1) return new HttpResponse(null, { status: 404 });
    actions[idx] = { ...body, name: params.name as string };
    saveActions(actions);
    return HttpResponse.json(actions[idx]);
  }),

  http.delete('/api/actions/:name', async ({ params }) => {
    await delay(LATENCY);
    const actions = getActions().filter(a => a.name !== params.name);
    saveActions(actions);
    return new HttpResponse(null, { status: 204 });
  }),

  // ============================================================
  // Explorer — Resource Instances
  // ============================================================

  http.get('/api/instances', async () => {
    await delay(LATENCY);
    return HttpResponse.json(mockResourceInstances);
  }),

  // ============================================================
  // Functions
  // ============================================================

  http.get('/api/functions', async () => {
    await delay(LATENCY);
    return HttpResponse.json(mockFunctions);
  }),

  // ============================================================
  // Audit Events
  // ============================================================

  http.get('/api/events', async () => {
    await delay(LATENCY);
    return HttpResponse.json(mockEvents);
  }),
];
