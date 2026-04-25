---
canonical_id: 01KQ12YDWBH45HW6DN4SD130S8
title: React Best Practices
type: article
cluster: frontend
status: active
date: '2026-04-25'
tags:
- react
- frontend
- hooks
- state-management
- typescript
summary: React in 2026 — Server Components, the hooks that matter, state-management
  decisions (URL > local > zustand > redux), and the patterns that age well.
related:
- WebPerformanceOptimization
- DesignSystems
- TypeSystemsComparison
- SinglePageApplicationArchitecture
hubs:
- Frontend Hub
---
# React Best Practices

React in 2026 looks different from React in 2018. Server Components shipped, Suspense matured, the recommended state management consensus moved decisively away from Redux for new apps. Many "best practices" tutorials are out of date. This page is what's actually true now.

## The mental model that matters

A React component is a function from props to UI. Re-running the function with the same props should produce the same output. Side effects (data fetching, subscriptions, timers) live in `useEffect` and equivalents.

The hard part is managing *change*: when state updates, components re-render, effects fire, and you have to reason about the order. Most React bugs are about ordering of state updates, effects, and renders.

Internalise: **render-as-derivation**. UI is a function of state. Don't store derived data in state; compute it during render. The bugs you avoid are huge.

## Server Components

Since React 19 (2024), Server Components are stable. A component runs on the server, generates HTML, never ships its JS to the client. Client components are interactive and ship.

Decisions:

- **Default to server components** for any UI that doesn't need interactivity. Cuts bundle size dramatically.
- **`"use client"` directive** opts a component (and its descendants) into client rendering. Use sparingly.
- **Data fetching in server components** is simpler — `await db.query(...)` in the component body. No `useEffect`, no loading states for the initial render.

Frameworks: Next.js (App Router) and Remix are the production options. Vanilla React with Server Components is technically possible but rarely worth it.

## Hooks that matter

Most apps use four hooks 90% of the time.

### useState

Local component state. Should be flat (one value per piece of state); arrays of primitives are fine; deeply nested objects often signal a need for refactor.

```typescript
const [count, setCount] = useState(0);
const [user, setUser] = useState<User | null>(null);
```

If you're reaching for `useReducer`, ask first whether you'd be better served by a state manager (Zustand) or by lifting state up to a parent.

### useEffect

Side effects after render. The most-misused hook.

```typescript
useEffect(() => {
    const subscription = fetchData(id);
    return () => subscription.cancel();
}, [id]);
```

Common mistakes:

- **Missing dependencies.** ESLint rule (`react-hooks/exhaustive-deps`) catches these. Don't disable.
- **Using `useEffect` for derived state.** Compute during render. `useMemo` if expensive.
- **Using `useEffect` for transformations.** Map your props in render. Effects are for *side effects* (subscriptions, timers, manual DOM, fetching that can't run on the server).

### useMemo and useCallback

Caching. `useMemo` for values; `useCallback` for functions.

Both are micro-optimisations. Don't add them prophylactically. Add them when:
- A child component is wrapped in `React.memo` and depends on the value/function.
- The computation is expensive and re-runs unnecessarily.

Most components don't need either. Adding them everywhere makes the code worse.

### useContext

Pass values through the component tree without prop drilling.

Limit context to:
- Truly app-wide data (auth, theme, locale).
- Things that change rarely (every consumer re-renders on context change).

For frequently-changing app-wide data, use a state manager (Zustand) instead of context.

## State management: the modern hierarchy

Use the simplest tool that handles your case:

1. **URL state.** Filters, current page, search, tabs. Encode in query params. Free synchronisation across tabs, free deep-linking, free browser back/forward.
2. **Local component state.** Form inputs, hover, expanded/collapsed.
3. **Lifted state.** Multiple components share state → put it in their nearest common ancestor.
4. **Server state.** Data fetched from APIs. Use TanStack Query (formerly React Query) or SWR. *Not* Redux. Not your own `useEffect` + `useState`.
5. **Global client state.** Truly cross-component, mutable, app-wide. Zustand, Jotai, Valtio. Tiny, focused libraries.
6. **Redux.** Very large apps with complex domain models, audit / time-travel debugging needs, or mature Redux teams. Otherwise: skip.

Most apps in 2026 use TanStack Query for server state + Zustand (or nothing) for client state. Redux Toolkit is fine if you're already on it; rarely the right choice for new apps.

## Component patterns that age well

### Composition over prop explosion

A component that takes 15 props is a refactor opportunity:

```tsx
// Bad: many specific props
<Dialog 
  title="..." onClose={...} hasCloseButton 
  showFooter footerText="..." footerButtonText="..."
  ...
/>

// Better: composition
<Dialog onClose={...}>
  <DialogTitle>...</DialogTitle>
  <DialogBody>...</DialogBody>
  <DialogFooter>
    <Button>...</Button>
  </DialogFooter>
</Dialog>
```

Children render naturally; the parent doesn't need to know what's inside.

### Custom hooks for behaviour

When two components share logic, extract to a custom hook. Custom hooks own state, effects, and computed values; components consume them.

```tsx
function usePagination(initialPage = 1, pageSize = 20) {
  const [page, setPage] = useState(initialPage);
  // ...
  return { page, setPage, offset: (page - 1) * pageSize, pageSize };
}
```

Custom hooks compose. A `useUserOrders(userId)` hook can call `useQuery(...)` from TanStack Query inside; consumers just call `useUserOrders(42)`.

### Forms

For non-trivial forms, use a form library: React Hook Form is the production standard. Validation: Zod schemas, integrated.

```tsx
const schema = z.object({
  email: z.string().email(),
  age: z.number().int().min(18),
});

const { register, handleSubmit, formState: { errors } } = useForm({
  resolver: zodResolver(schema),
});
```

Saves immense amounts of boilerplate, gets validation right, integrates with TypeScript.

### Errors and Suspense

`<ErrorBoundary>` for catching errors. `<Suspense>` for handling pending states. With React 19, both are first-class.

Pattern:

```tsx
<Suspense fallback={<Skeleton />}>
  <ErrorBoundary fallback={<ErrorMessage />}>
    <UserDetail userId={42} />
  </ErrorBoundary>
</Suspense>
```

This is the modern replacement for `if (loading) ... else if (error) ... else ...` ladders inside components.

## TypeScript with React

Use it. The marginal cost of TypeScript in a new React project is approximately zero. The marginal benefit grows with codebase size; under-rated by people who haven't done it on a large app.

Conventions:

- Components typed as `React.FC<Props>` or just regular functions with typed `props: Props`. Both work; community has settled on regular functions.
- `useState<Type>(initial)` when initial is null/undefined and the type can't be inferred.
- Refs typed: `useRef<HTMLInputElement>(null)`.
- Strict null checks on; generally enable `strict: true` in tsconfig.

## Performance, when it matters

Most React performance work is unnecessary. Profile first.

When it does matter:

- **Big lists.** Use `react-window` or `@tanstack/react-virtual` for virtualisation. Rendering 10,000 DOM nodes is not free.
- **Frequent re-renders.** Profile with React DevTools profiler; identify the component re-rendering too often; add `React.memo` and proper memoisation.
- **Slow renders.** Often a single component doing expensive work synchronously. Move to `useDeferredValue`, `startTransition`, or web worker.
- **Bundle size.** Use Server Components for non-interactive UI; lazy-load heavy components with `React.lazy`; analyse with `@next/bundle-analyzer` or equivalent.

See [WebPerformanceOptimization].

## Anti-patterns

- **`useEffect` for derived state.** Compute during render.
- **State for things you can derive.** Same.
- **`useState` for mutable refs.** Use `useRef`. Don't update state when you don't need to re-render.
- **Setting state in render.** Infinite loop. The error is loud; the bug is sometimes subtle (called inside a function called during render).
- **Deeply nested state.** Splits, lifts, or flatten.
- **Prop drilling 5+ levels.** Time for context or state manager.
- **Inline functions creating new objects every render** when a memoized child depends on them. `useMemo` / `useCallback` if you've measured a problem.
- **Class components in new code.** Functional components + hooks have been the recommendation since 2019. Class components remain only in legacy.
- **Direct DOM manipulation when React would handle it.** Refs are an escape hatch; not the default.

## Testing

For component testing, React Testing Library + Vitest or Jest. The core idea: test behaviour, not implementation. "When the user clicks this, that text appears" — not "when the state updates, the JSX rerenders with that property."

```tsx
it('shows error when login fails', async () => {
  render(<LoginForm onSubmit={() => Promise.reject('bad creds')} />);
  await user.type(screen.getByLabelText(/email/i), 'a@b.c');
  await user.type(screen.getByLabelText(/password/i), 'x');
  await user.click(screen.getByRole('button', { name: /submit/i }));
  expect(await screen.findByText(/bad creds/i)).toBeInTheDocument();
});
```

For E2E tests, Playwright is the consensus pick; Cypress is fine if you're already on it.

## Frameworks

- **Next.js (App Router)** — production default for new apps. Server Components, full-stack, mature.
- **Remix** — alternative to Next; web-fundamentals focused.
- **Vite + React** — for SPAs that don't need a full framework. Fast dev experience.
- **CRA (Create React App)** — deprecated. Don't start new projects with it.

For most new React apps in 2026: Next.js with App Router unless there's a specific reason not to.

## Further reading

- [WebPerformanceOptimization] — when React perf matters
- [DesignSystems] — components reusable across products
- [TypeSystemsComparison] — TypeScript context
- [SinglePageApplicationArchitecture] — broader SPA architecture
