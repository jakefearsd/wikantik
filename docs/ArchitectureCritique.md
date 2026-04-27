# Architectural Critique: Wikantik Knowledge Engine

## Executive Summary
Wikantik represents a sophisticated modernization of a legacy codebase (JSPWiki). It successfully introduces "agent-grade" capabilities—such as hybrid retrieval, knowledge graphs, and semantic indexing—into a Java 21/Jakarta EE environment. The code quality is high, characterized by strong design patterns and a robust testing culture. 

However, the project is currently hitting an architectural ceiling caused by its **manual service discovery and dependency orchestration model**.

---

## 1. The Core Problem: The "Managed Monolith" (WikiEngine)
The biggest architectural flaw is the **Manager Registry pattern** within `com.wikantik.WikiEngine`. 

While the project has successfully moved interfaces to `wikantik-api`, the implementation still relies on a `Map<Class<?>, Object> managers` registry. This approach, known as the **Service Locator anti-pattern**, creates several systemic issues:

### A. Fragile Initialization Ordering (The "Phase" Problem)
Dependency wiring is currently hard-coded in `WikiEngine.initialize()` and `initKnowledgeGraph()`. This has forced a concept of "Phases":
*   **Phase 1 managers** (e.g., `CommandResolver`) are available during construction.
*   **Phase 2 managers** (e.g., `SearchManager`, `PageManager`) are wired later.
*   **Late-bound services** (e.g., `HybridRetrieval`) require manual "wiring" calls after the engine is partially started.

This makes the system extremely brittle. Adding a new dependency between two managers often requires a surgery of the `initialize()` method to ensure the provider is registered before the consumer tries to fetch it.

### B. "Mocking the World" in Tests
Because managers often reach back into the `WikiEngine` to look up their siblings (e.g., `engine.getManager(PageManager.class)`), unit tests cannot easily isolate a single component. To test a simple logic change, you must often instantiate a full `WikiEngine` or a complex mock engine that simulates the registry, leading to slow and bloated test suites.

### C. Hidden Circular Dependencies
Manual wiring hides circular dependencies until runtime. A component might successfully initialize but fail later when it calls a sibling that hasn't been fully registered, leading to `NullPointerException` or `IllegalStateException` during bootstrap that are difficult to debug.

---

## 2. Recommendation: Dependency Injection with Google Guice

To resolve this, Wikantik should transition from a **Service Locator** to a **Dependency Injection (DI)** container.

### Why Google Guice (and not Spring)?
For most enterprise applications, **Spring** is the default choice. However, for a **modular engine/framework** like Wikantik, **Google Guice** is a superior fit.

| Feature | Google Guice | Spring Framework |
| :--- | :--- | :--- |
| **Footprint** | Tiny (~1MB), zero transitive dependencies. | Large, brings a massive ecosystem of dependencies. |
| **Philosophy** | **Library-first.** Guice stays out of your way and just does DI. | **Framework-first.** Spring wants to own the entire application lifecycle. |
| **Configuration** | Type-safe Java Modules. Errors are caught at startup. | Often relies on classpath scanning, proxying, and runtime "magic." |
| **Embeddability** | Designed to be embedded inside other applications (like an engine). | Difficult to "nest" or embed without the Spring container taking over. |

**The Case for Guice in Wikantik:**
Wikantik is an *engine* designed to be deployable as a WAR or embedded in other tools. Guice allows us to define `WikiModule`, `KnowledgeModule`, and `SearchModule`. It perfectly handles the "Phase" problem using `Providers`: if `Manager A` needs `Manager B`, Guice ensures `B` is ready, or provides a `Provider<B>` to handle circularity or late-binding gracefully.

---

## 3. What the Change Simplifies

### A. Declarative Lifecycles
Instead of a 300-line `initialize()` method with manual `new` calls and `managers.put()` statements, you simply declare dependencies in constructors:
```java
@Inject
public DefaultPageManager(PageProvider provider, SearchManager searchMgr) { ... }
```
The container handles the graph traversal and instantiation order automatically.

### B. Clean Testing
You can test `DefaultPageManager` by passing in a mock `PageProvider` and a mock `SearchManager` directly to the constructor. No `WikiEngine` mock is required.

### C. True Modularity
Modules can be swapped via configuration. Want to replace the `LuceneSearchProvider` with an `ElasticSearchProvider`? You simply swap the Guice Module in the bootstrap, and every component that `@Injects` a `SearchProvider` gets the new version without a single line of code change in the core engine.

### D. Elimination of "Phases"
The concept of Phase 1/Phase 2 disappears. Guice builds the dependency graph at startup. If there is a missing dependency or a hard circular link, the application fails fast with a clear error message before the first request ever hits the server.

---

## 5. Incremental Adoption Strategy (The "Bridge-First" Approach)

Refactoring a 20-year-old initialization sequence in one "big bang" is high-risk. Instead, Wikantik should adopt an incremental strategy that allows the legacy registry and the DI container to coexist.

### Phase 1: The Hybrid Bridge (START HERE)
**Goal:** Introduce the Guice `Injector` as a primary lookup mechanism within the existing `WikiEngine`.

1.  **Initialize the Injector:** In `WikiEngine.initialize()`, create a Guice `Injector` using a new `WikiModule`.
2.  **Refactor `getManager`:** Modify the `getManager(Class<T> clazz)` method to try the `injector` first, and fall back to the legacy `managers` map only if Guice cannot satisfy the request.
3.  **Result:** You can now move any manager to Guice without breaking its consumers.

### Phase 2: Leaf-Node Migration
**Goal:** Migrate components with no internal wiki dependencies.

1.  **Select Targets:** Start with managers like `InternationalizationManager`, `ProgressManager`, or `VariableManager`.
2.  **Refactor to Constructor Injection:** Change their constructors to accept dependencies directly (e.g., `Properties`, `ServletContext`) and mark them with `@Inject`.
3.  **Bind in Module:** Register them in `WikiModule`.
4.  **Cleanup:** Remove the manual `new` and `managers.put()` calls from `WikiEngine`.

### Phase 3: Sibling Migration & Providers
**Goal:** Migrate "Heavy" managers (`PageManager`, `SearchManager`) that have interdependent or circular relationships.

1.  **Use Providers:** For managers that depend on each other, use Guice `Provider<T>` to break circularity.
2.  **Inject the Engine:** Allow components to `@Inject Engine` so legacy logic can still interact with the global context while they wait for their own migration.

### Phase 4: Interface-Only Exposure
**Goal:** Enforce the `wikantik-api` boundary.

1.  **Hide Implementations:** Update Guice bindings to explicitly bind the API interface to the internal implementation (e.g., `bind(PageManager.class).to(DefaultPageManager.class)`).
2.  **Strict Typing:** Remove implementation classes from constructor arguments throughout the codebase, replacing them with their API counterparts.

### Phase 5: Deprecation and Cleanup
**Goal:** Complete the transition.

1.  **Empty the Registry:** Once the legacy `managers` map is empty, remove it entirely.
2.  **Monolith Reduction:** `WikiEngine` becomes a clean lifecycle manager that simply initializes the Injector and handles shutdown signals.
3.  **Final Verification:** All unit tests should now be able to run by instantiating managers directly with mocks, without any reference to `WikiEngine` or its legacy registry.
