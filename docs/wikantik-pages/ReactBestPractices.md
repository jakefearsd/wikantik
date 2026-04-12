---
title: React Best Practices
type: article
tags:
- hook
- composit
- state
summary: 'React Best Practices: The Art and Science of Hooks Composition Welcome.'
auto-generated: true
---
# React Best Practices: The Art and Science of Hooks Composition

Welcome. If you've reached this document, you're not here for the boilerplate tutorials explaining `useState` or the basic structure of `useEffect`. You understand the fundamental contract of React Hooks: they are functions that let you "hook into" React state and lifecycle features from functional components. You know that custom hooks are the primary mechanism for extracting and reusing stateful logic, thereby achieving true separation of concerns.

However, for the expert researching cutting-edge patterns, the challenge isn't merely *creating* a custom hook; it's mastering the **composition** of multiple, disparate hooks into a single, cohesive, highly performant, and architecturally sound abstraction.

This tutorial is not a checklist. It is a deep dive into the theoretical underpinnings, the practical mechanics, the performance pitfalls, and the advanced architectural patterns required to treat hook composition not as a convenience, but as a rigorous discipline of software engineering. We are moving beyond "reusability" and into "compositional resilience."

---

## I. The Theoretical Foundation: Why Composition Matters More Than Isolation

Before we write a single line of pseudo-code, we must establish the theoretical framework. Why is composing hooks superior to simply writing a massive, monolithic hook?

### A. Abstraction Layers and Cognitive Load Management

In [software architecture](SoftwareArchitecture), abstraction is about hiding complexity. A well-designed custom hook acts as a façade, presenting a simple, clean API (`useDataFetcher(endpoint)`) while internally managing a complex orchestration of state, side effects, loading states, error handling, and cancellation logic.

**Composition** is the mechanism by which we stack these façades.

Consider a complex feature, like a real-time, paginated, filtered search interface. This feature requires:
1.  Fetching logic (caching, retries).
2.  State management for pagination (`page`, `limit`).
3.  State management for filtering/sorting (debouncing input).
4.  Side effect management (debouncing the fetch trigger).
5.  Error/Loading state aggregation.

If we write this as one giant hook, the resulting function signature becomes a nightmare, and debugging becomes a labyrinth. By composing smaller, focused hooks—`useDebounce`, `usePagination`, `useApiFetcher`—we achieve **orthogonal composition**. Each hook solves one concern, and the final composite hook merely orchestrates their inputs and outputs.

> **Expert Insight:** The goal of composition is to ensure that the resulting composite hook's API surface area is as small and predictable as possible, even if its internal implementation is a symphony of dozens of smaller, specialized hooks.

### B. The Contract of the Hook: Inputs, Outputs, and Dependencies

Every custom hook must adhere to a strict contract:
1.  **Inputs:** It must accept only primitive values, hooks, or other hooks as arguments.
2.  **Outputs:** It must return a predictable, cohesive set of values (e.g., `{ data, isLoading, error }`).
3.  **Dependencies:** Its internal logic must manage its dependencies explicitly, usually via the dependency array of `useEffect` or `useMemo`.

Composition forces us to treat the *output* of one hook as the *input* to another. This creates a data flow graph within the component body, which is far more traceable than deeply nested component logic.

### C. Composition vs. Inheritance (The Anti-Pattern Discussion)

A common conceptual error for those transitioning from OOP backgrounds is thinking about "hook inheritance." Hooks do not inherit state or methods in the traditional sense. They operate within the scope of the component render cycle.

*   **Inheritance (OOP):** `Child` *is a* `Parent`.
*   **Composition (React Hooks):** `CompositeHook` *uses the logic of* `HookA` and `HookB`.

We are assembling *behavior*, not types. The power lies in the fact that the execution order of the hooks within the component body dictates the execution order of their side effects, which is a deterministic, predictable pattern that OOP inheritance struggles to replicate cleanly in a functional context.

---

## II. Mechanics of Composition: Building Blocks and Patterns

To achieve mastery, we must dissect the composition process into its constituent technical patterns.

### A. Composition of State Logic (The State Aggregator Pattern)

This is the most common form. We take multiple pieces of state management—often derived from different sources—and combine them into one logical unit.

**Scenario:** Building a complex form that needs local state, remote state validation, and global user context.

**The Composition:**
1.  `useLocalFormState()`: Manages transient, UI-specific state (e.g., input drafts).
2.  `useValidation(schema)`: Manages asynchronous validation state (e.g., API calls to check uniqueness).
3.  `useAuthContext()`: Reads global state (e.g., `user.permissions`).

The composite hook, `useComplexFormState()`, doesn't just call these three hooks sequentially; it *orchestrates* their interaction.

**Pseudo-Code Illustration (Conceptual):**

```typescript
// 1. Dependencies are gathered
const { draft, setDraft } = useLocalFormState();
const { isValid, validationError } = useValidation(schema);
const { user } = useAuthContext();

// 2. The composite hook calculates derived state based on inputs
const isSubmitting = !user.canSubmit || !isValid;

// 3. The composite hook returns the unified API
return {
    formData: draft,
    isValid: isValid && user.canSubmit,
    submit: async (payload) => {
        // Logic that depends on all three states
        if (!isValid) throw new Error("Validation failed.");
        // ... API call using user token ...
    }
};
```

**Expert Consideration: State Coherency:** The greatest risk here is **stale closure capture**. If `useValidation` relies on a prop or state derived from the component scope, and that dependency isn't correctly passed or memoized, the composite hook will operate on outdated data, leading to silent, catastrophic bugs that only manifest under specific user journeys. Always audit the dependency array of the *outermost* hook wrapper.

### B. Composition of Side Effects (The Effect Orchestrator Pattern)

This is where things get truly advanced. We are composing *side effects*, not just state. This involves coordinating multiple `useEffect` calls, ensuring they run in the correct sequence, handle race conditions, and manage cleanup across different concerns.

**Scenario:** Implementing a data synchronization mechanism that must:
1.  Fetch initial data on mount.
2.  Subscribe to a WebSocket stream for real-time updates.
3.  Debounce user input changes to trigger a secondary, paginated fetch.
4.  Clean up *all* subscriptions and listeners when the component unmounts or dependencies change.

**The Composition:** The composite hook must wrap the lifecycle management of all constituent effects.

```typescript
const useSyncData = (endpoint: string, wsUrl: string) => {
    // 1. Primary Fetch Effect (Handles initial load)
    useEffect(() => {
        const controller = new AbortController();
        const signal = controller.signal;
        // Fetch logic using signal...
        return () => controller.abort(); // Cleanup 1
    }, [endpoint]);

    // 2. WebSocket Subscription Effect (Handles persistent connection)
    useEffect(() => {
        const ws = new WebSocket(wsUrl);
        ws.onmessage = (event) => { /* ... update state ... */ };
        // Cleanup 2: ws.close()
        return () => { /* Cleanup logic for WS */ };
    }, [wsUrl]);

    // 3. Debounced Effect (Handles user interaction)
    useEffect(() => {
        const handler = setTimeout(() => {
            // Trigger secondary fetch based on debounced input
        }, 300);
        return () => clearTimeout(handler); // Cleanup 3
    }, [debouncedInput]);

    // The composite hook returns the aggregated state from all three sources
    return { data: combinedState, isLoading: isLoadingFromFetch || isConnecting };
};
```

**The Expert Challenge: Cleanup Management:** The cleanup function returned by `useEffect` is not merely for the effect it wraps. When composing effects, the cleanup function must be a **composition of cleanups**. If Effect A sets up a listener, and Effect B sets up a subscription, the composite hook's cleanup must tear down *both* listeners and *both* subscriptions, regardless of which effect triggered the dependency change. Failure here leads to memory leaks and unpredictable state corruption.

### C. Composition of Context Consumers (The Dependency Resolver Pattern)

Context is powerful, but it is notoriously leaky when misused. Composing hooks that consume context requires treating the context provider as a managed dependency, not just a global variable.

**The Problem:** If `HookA` consumes `UserContext` and `HookB` consumes `ThemeContext`, and `HookA`'s logic depends on the theme (e.g., "Show admin panel only if user is admin AND theme is dark"), simply calling both hooks is insufficient. The composite hook must enforce the *order of evaluation* and the *dependency chain*.

**The Solution: Contextual Dependency Injection:**
Instead of letting the component body implicitly read context, the composite hook should accept the necessary context *values* as explicit arguments, or, if using a pattern like `useContext`, it must wrap the consumption within a controlled dependency check.

```typescript
// Bad Practice (Implicit Dependency):
const { user } = useContext(UserContext);
const { theme } = useContext(ThemeContext);
// If UserContext changes, but ThemeContext doesn't, the component might re-render unnecessarily,
// or worse, the logic might fail if the context provider isn't correctly memoized.

// Better Practice (Explicit Composition):
const useAdminPanel = (user: User, theme: Theme) => {
    // Logic now explicitly depends only on the passed values
    if (!user.isAdmin || theme !== 'dark') return null;
    return <AdminComponent />;
};

// Component usage:
const { user } = useUser(); // Assume this hook fetches and returns the user object
const { theme } = useTheme(); // Assume this hook fetches and returns the theme object
return useAdminPanel(user, theme);
```
By forcing the dependency resolution up to the component level and passing the resolved values into the composite hook, we gain explicit control over when the composite logic re-evaluates.

---

## III. Advanced Architectural Patterns in Composition

To truly operate at an expert level, we must address the meta-patterns—the architectural decisions surrounding the hooks themselves.

### A. The State Machine Hook Pattern (`useStateMachine`)

For any component whose behavior can be modeled as a finite state machine (FSM), composing hooks is the cleanest approach. The FSM hook acts as the central arbiter, consuming inputs (events) and emitting derived state.

**Composition Strategy:**
1.  **Event Listener Hook:** A hook that listens for external events (e.g., `useWebSocketListener`, `useFormSubmitListener`).
2.  **Transition Logic Hook:** A pure function hook that takes the current state and an event, and returns the *next* state and any required side effects.
3.  **State Provider Hook:** The composite hook that manages the state using `useReducer` and calls the transition logic hook whenever an event is received.

**Why this is superior:** It eliminates complex nested `useEffect` logic that tries to manage state transitions based on multiple asynchronous inputs. The FSM hook forces the developer to define the *entire state graph* upfront, making the system deterministic and testable.

### B. The Observable/Stream Hook Pattern (`useObservable`)

In modern, highly reactive applications (especially those interacting with real-time data or complex data pipelines), state often doesn't change based on discrete user actions; it changes based on an *external stream* of data.

**Composition Strategy:**
We compose the hook by integrating a reactive stream library (like RxJS or a custom Observable pattern) directly into the hook's lifecycle.

1.  **Subscription Hook:** Manages the connection to the stream (e.g., `useWebSocketSubscription`).
2.  **Transformation Hook:** Applies operators (`map`, `filter`, `debounceTime`) to the stream's emissions. This is where the heavy lifting happens, often using `useMemo` to cache the transformation pipeline itself.
3.  **State Hook:** Consumes the final, transformed value from the stream and updates React state.

The composite hook `useObservableData(streamSource, pipeline)` effectively becomes a bridge: it subscribes to the external world, processes the data through a defined pipeline, and exposes the result as standard React state.

### C. Composition for Performance: The Memoization Cascade

Performance optimization in hooks composition is not about calling `useMemo` everywhere; it's about understanding *what* needs to be memoized and *where* the dependency graph breaks.

1.  **Memoizing Derived State:** If `HookA` calculates `A_derived` and `HookB` calculates `B_derived`, and the composite hook needs `A_derived + B_derived`, you must wrap the combination in `useMemo`.
    ```typescript
    const combinedValue = useMemo(() => {
        // This entire block only re-runs if A_derived or B_derived changes.
        return calculateComplexMetric(A_derived, B_derived);
    }, [A_derived, B_derived]);
    ```

2.  **Memoizing Hook Outputs (The Hard Part):** Sometimes, the *result* of a hook call itself needs to be stable across renders, even if the hook's internal dependencies change slightly. This is rare and dangerous, but sometimes necessary when integrating with external, non-React libraries that rely on object identity. In such cases, you might wrap the entire hook execution within a custom memoization layer, though this often signals that the hook itself is violating the principles of React's rendering model.

3.  **Dependency Array Discipline:** The most common performance killer is an overly broad dependency array (`[]` when it shouldn't be, or including an object/array literal that is recreated on every render). For experts, the rule is: **If the dependency is an object or array, it must be memoized *before* being passed to the hook.**

---

## IV. Pitfalls, Anti-Patterns, and Expert Guardrails

This section is crucial. Knowing how to build a complex hook is one thing; knowing when *not* to build one, or when a hook is fundamentally flawed, is the mark of an expert.

### A. The Over-Engineering Trap (The "Hook for Everything" Syndrome)

This is the most pervasive anti-pattern. Developers, armed with the power of custom hooks, often succumb to the urge to abstract *every* piece of logic.

**The Test:** Before creating a new hook, ask: "If I remove this hook, does the component become unreadable, or does it just become slightly longer?"

If the answer is "slightly longer," you are over-engineering. The cost of maintaining the abstraction layer (the boilerplate, the documentation, the testing matrix) outweighs the benefit of the perceived cleanliness.

**Guardrail:** Reserve custom hooks for logic that meets one or more of these criteria:
1.  It involves complex, multi-step side effects (e.g., fetching, subscribing, cleaning up).
2.  It manages state derived from multiple, disparate sources (e.g., Context + Props + Local State).
3.  It represents a distinct, reusable *domain concept* (e.g., `useDebouncedSearch`, `useLocalStorage`).

### B. Context Hell and Prop Drilling Reversal

While Context solves prop drilling, composing hooks that rely heavily on context can lead to a "Context Hell" where the component tree becomes a dependency graph of providers.

**The Danger:** If a deeply nested component relies on `useContext(A)` and `useContext(B)`, and the provider for `A` is far removed from the provider for `B`, changes in one provider might trigger unnecessary re-renders or, worse, cause the consumer hook to read stale context values if the provider itself isn't memoized correctly.

**The Expert Solution: State Colocation and Selector Patterns:**
Instead of relying on multiple, disparate context providers, consider consolidating related state into a single, larger context object, and then using **selector functions** within your custom hook.

```typescript
// Instead of:
// const { user } = useContext(UserContext);
// const { settings } = useContext(SettingsContext);

// Use a single, selector-driven hook:
const useCombinedState = () => {
    const context = useContext(CombinedStateContext);
    // The selector function ensures that the component only re-renders if the specific
    // derived value (user.role === 'admin' && settings.notificationsEnabled) changes.
    return useMemo(() => ({
        canAccess: context.user.role === 'admin' && context.settings.notificationsEnabled
    }), [context.user.role, context.settings.notificationsEnabled]);
};
```
This pattern forces the dependency tracking to happen at the *selection* level, not the *context consumption* level.

### C. The Asynchronous Composition Nightmare (Race Conditions)

This is the most subtle and dangerous area. When composing hooks that involve asynchronous operations (API calls, timers, WebSockets), race conditions are inevitable if not explicitly managed.

**The Principle of Cancellation:** Every asynchronous operation initiated within a hook must have a corresponding, guaranteed cleanup mechanism.

When composing, you must track the *latest* initiating event. If a user types 'A', triggering Fetch 1, and then types 'B' before Fetch 1 resolves, the hook must ensure that Fetch 1's result *never* updates the state if Fetch 2 (triggered by 'B') has already started or completed.

**Implementation Detail:** This requires passing an `AbortController` signal or a unique request ID through the entire chain of composed effects. The final state setter must check this ID against the ID of the operation that *actually* completed.

---

## V. Future-Proofing and The Next Frontier: React 19+ Integration

For researchers researching new techniques, the current state of React development—particularly the move toward Server Components and concurrent rendering models—demands an update to our understanding of hook composition.

### A. Server Components and Client Boundaries

The introduction of React Server Components (RSCs) fundamentally changes where state and side effects can live. Hooks, by definition, are client-side mechanisms.

**The Composition Shift:**
1.  **Server-Side Logic:** Data fetching, heavy computation, and initial state derivation *must* happen on the server (or in a dedicated data layer). This logic should be encapsulated in functions that *return* data structures, not hooks.
2.  **Client-Side Logic (The Hook's Role):** The custom hook's job shrinks to managing *client-side interactivity* and *local state* based on the data provided by the server.

**Example:**
*   **Old Way:** `useFetchData(endpoint)` (Runs on client, handles fetching, loading, error).
*   **New Way:** The Server Component fetches data and passes the resolved data object as a prop: `<ClientComponent initialData={serverFetchedData} />`. The custom hook then becomes `useClientInteractivity(initialData)`—it *consumes* the resolved data rather than *fetching* it.

This compositional shift means that the most robust hooks are those that are **data-agnostic** regarding their source. They accept data, and they manage the *behavior* around that data.

### B. Suspense and Composable Loading States

Suspense is the mechanism for handling asynchronous boundaries. When composing hooks, you must compose the *loading state* itself.

If `HookA` fetches data that suspends, and `HookB` fetches configuration that also suspends, the composite hook cannot simply return `isLoading: true` if one fails and the other succeeds.

**The Compositional Solution:** The composite hook must manage a **Promise Waterfall**. It must wrap the execution of its constituent hooks in a mechanism that waits for *all* necessary promises to resolve before rendering the final state, and it must correctly bubble up the *first* failure encountered across the entire dependency graph.

### C. TypeScript Rigor in Composition

For experts, TypeScript is not optional; it is the scaffolding that prevents compositional failure.

When composing hooks, the return type of the composite hook must be a single, unified interface. Every intermediate hook must be typed to guarantee that its output matches the expected input type of the next hook in the chain.

This forces the developer to treat the entire composition as a single, type-safe pipeline, eliminating the ambiguity that plagues runtime JavaScript hook composition.

---

## VI. Conclusion: The Philosophy of Compositional Mastery

To summarize this exhaustive exploration: Hooks composition is not merely about writing `useHookA()` followed by `useHookB()`. It is a sophisticated act of **architectural synthesis**.

A master practitioner views the component body not as a sequence of calls, but as a **data flow graph**.

1.  **Identify Concerns:** Decompose the feature into its smallest, independent, stateful, or side-effect-generating units.
2.  **Isolate Logic:** Encapsulate each unit into a minimal, pure, and highly focused custom hook.
3.  **Orchestrate the Graph:** Write the final composite hook that acts as the conductor. This conductor manages the dependency resolution, the lifecycle cleanup, the error propagation, and the final state aggregation from all constituent parts.
4.  **Guard Against Hubris:** Constantly test for the "Over-Engineering Trap." If the abstraction adds more cognitive overhead than it removes, simplify.

Mastering this composition means achieving a level of code that is not just *functional*, but *provably correct* across complex, asynchronous, and evolving state landscapes. It requires moving from thinking like a React developer to thinking like a reactive systems architect.

If you can confidently design, implement, and debug a composite hook that manages concurrent API calls, WebSocket subscriptions, local form state, and global context dependencies—all while respecting the cleanup contract of the React lifecycle—then you are operating at the apex of modern React development.

Now, go build something that breaks the compiler just to prove you can fix it.
