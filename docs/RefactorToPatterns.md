# Gang of Four Design Pattern Refactoring Opportunities

This document outlines practical refactoring opportunities to apply well-known Gang of Four design patterns to improve the Wikantik codebase architecture.

## Overview

Based on comprehensive analysis of the Wikantik codebase, six high-value refactoring opportunities have been identified. These patterns would improve code maintainability, testability, and extensibility.

---

## Priority 1: Decorator Pattern Generalization for Providers

### Current State

`CachingProvider` wraps `PageProvider` but this pattern isn't generalized.

**Code Location:** `wikantik-main/src/main/java/org/apache/wiki/providers/CachingProvider.java:63-102`

### Problem

- CachingProvider is tightly coupled to EhCache implementation
- No easy way to add other cross-cutting concerns (logging, metrics, access control)
- Attachment caching uses a completely separate class (`CachingAttachmentProvider`)

### Recommended Pattern: Generalized Provider Decorator

```java
// Base decorator for PageProvider
public abstract class PageProviderDecorator implements PageProvider {
    protected final PageProvider delegate;

    protected PageProviderDecorator(PageProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public void putPageText(Page page, String text) throws ProviderException {
        delegate.putPageText(page, text);
    }
    // ... delegate all other methods by default
}

// Specific decorators
public class CachingPageProviderDecorator extends PageProviderDecorator { ... }
public class MetricsPageProviderDecorator extends PageProviderDecorator { ... }
public class LoggingPageProviderDecorator extends PageProviderDecorator { ... }
```

### Benefits

- Single Responsibility Principle - each decorator handles one concern
- Easy to compose decorators: `new Caching(new Logging(new FileSystem()))`
- Same pattern applies to AttachmentProvider

**Effort:** Medium | **Impact:** High

---

## Priority 2: Abstract Factory for Storage Backends

### Current State

Providers are instantiated independently via `ClassUtil.buildInstance()`:
- `PageManager` creates its `PageProvider`
- `AttachmentManager` creates its `AttachmentProvider`
- `SearchManager` creates its `SearchProvider`

**Code Location:** `wikantik-main/src/main/java/org/apache/wiki/WikiEngine.java:272-310`

### Problem

- No guarantee providers are compatible (e.g., JDBC page provider with file-based attachments)
- Configuration scattered across multiple properties
- Hard to add new storage backends (e.g., cloud storage)

### Recommended Pattern: Abstract Factory

```java
public interface StorageBackendFactory {
    PageProvider createPageProvider(Engine engine, Properties props);
    AttachmentProvider createAttachmentProvider(Engine engine, Properties props);
    SearchProvider createSearchProvider(Engine engine, Properties props);

    // Factory method to get appropriate factory
    static StorageBackendFactory forBackend(String backendType) {
        return switch(backendType) {
            case "filesystem" -> new FileSystemStorageFactory();
            case "jdbc" -> new JdbcStorageFactory();
            case "versioning" -> new VersioningFileStorageFactory();
            default -> throw new IllegalArgumentException("Unknown backend: " + backendType);
        };
    }
}

public class VersioningFileStorageFactory implements StorageBackendFactory {
    @Override
    public PageProvider createPageProvider(Engine engine, Properties props) {
        return new VersioningFileProvider();
    }

    @Override
    public AttachmentProvider createAttachmentProvider(Engine engine, Properties props) {
        return new BasicAttachmentProvider();
    }

    @Override
    public SearchProvider createSearchProvider(Engine engine, Properties props) {
        return new LuceneSearchProvider();
    }
}
```

### Benefits

- Ensures provider compatibility
- Simplifies configuration (one property instead of three)
- Easy to add new storage backends as a unit

**Effort:** Medium | **Impact:** High

---

## Priority 3: Builder Pattern for Engine Initialization

### Current State

`WikiEngine.initialize()` has 25+ sequential `initComponent()` calls with implicit ordering:

```java
initComponent( CommandResolver.class, this, props );
initComponent( CachingManager.class, this, props );
initComponent( PageManager.class, this, props );
// ... 20+ more
```

**Code Location:** `wikantik-main/src/main/java/org/apache/wiki/WikiEngine.java:272-310`

### Problem

- Dependencies between managers are implicit (comment says RenderingManager depends on FilterManager)
- Hard to test with partial initialization
- No way to customize initialization order

### Recommended Pattern: Builder with Dependency Declaration

```java
public class WikiEngineBuilder {
    private final Map<Class<?>, ManagerConfig> managerConfigs = new LinkedHashMap<>();

    public WikiEngineBuilder withManager(Class<?> managerClass, Class<?>... dependsOn) {
        managerConfigs.put(managerClass, new ManagerConfig(managerClass, dependsOn));
        return this;
    }

    public WikiEngine build(Properties props) throws WikiException {
        // Topological sort based on dependencies
        List<Class<?>> initOrder = resolveDependencyOrder(managerConfigs);

        WikiEngine engine = new WikiEngine();
        for (Class<?> manager : initOrder) {
            engine.initComponent(manager, props);
        }
        return engine;
    }
}

// Usage
WikiEngine engine = new WikiEngineBuilder()
    .withManager(CachingManager.class)
    .withManager(PageManager.class, CachingManager.class)
    .withManager(FilterManager.class, PageManager.class)
    .withManager(RenderingManager.class, FilterManager.class)
    .build(props);
```

### Benefits

- Explicit dependency declaration
- Automatic dependency resolution
- Easier testing with subset of managers

**Effort:** High | **Impact:** Medium

---

## Priority 4: Strategy Pattern for Property Caching

### Current State

`VersioningFileProvider.CachedProperties` is a single-entry cache:

```java
private static class CachedProperties {
    String m_page;
    Properties m_props;
    long m_lastModified;
}
private CachedProperties m_cachedProperties;  // Only ONE entry!
```

**Code Location:** `wikantik-main/src/main/java/org/apache/wiki/providers/VersioningFileProvider.java:697-720`

### Problem

- Only caches last-accessed properties file
- Accessing A→B→A causes 3 disk reads instead of 2
- Comment admits "there is likely to be little performance gain" but doesn't prove it

### Recommended Pattern: Strategy Pattern for Cache Implementation

```java
public interface PropertyCacheStrategy {
    Properties get(String page, Supplier<Properties> loader);
    void invalidate(String page);
    void clear();
}

// Single-entry (current behavior)
public class SingleEntryPropertyCache implements PropertyCacheStrategy { ... }

// LRU cache (new)
public class LruPropertyCache implements PropertyCacheStrategy {
    private final Map<String, CachedProperties> cache;
    private final int maxSize;
    // Uses LinkedHashMap with removeEldestEntry()
}

// No-op cache (for testing)
public class NoOpPropertyCache implements PropertyCacheStrategy {
    @Override
    public Properties get(String page, Supplier<Properties> loader) {
        return loader.get();
    }
}
```

### Benefits

- Configurable cache size via property
- Easy to benchmark different strategies
- Better separation of concerns

**Effort:** Low | **Impact:** Medium (synergizes with disk I/O optimizations)

---

## Priority 5: Type-Safe Event System

### Current State

Events use integer constants:

```java
public abstract class WikiEvent extends EventObject {
    public static final int ERROR = -99;
    public static final int UNDEFINED = -98;
    private int m_type = UNDEFINED;
}

// In WikiPageEvent
public static final int PAGE_LOCK = 11;
public static final int PAGE_UNLOCK = 12;
public static final int PAGE_REQUESTED = 20;
```

**Code Location:** `jspwiki-event/src/main/java/org/apache/wiki/event/WikiEvent.java`

### Problem

- No compile-time type safety
- Easy to use wrong event type constant
- `switch` statements on int values scattered through codebase
- Can't use generics for type-safe listeners

### Recommended Pattern: Type-Safe Event Hierarchy with Visitor

```java
// Sealed event hierarchy (Java 17+)
public sealed interface WikiEvent permits
    PageEvent, SecurityEvent, EngineEvent, WorkflowEvent {

    Object getSource();
    long getWhen();
    <T> T accept(WikiEventVisitor<T> visitor);
}

public sealed interface PageEvent extends WikiEvent permits
    PageLockEvent, PageUnlockEvent, PageSaveEvent, PageDeleteEvent {

    String getPageName();
}

public record PageSaveEvent(Object source, long when, String pageName, int version)
    implements PageEvent {

    @Override
    public <T> T accept(WikiEventVisitor<T> visitor) {
        return visitor.visitPageSave(this);
    }
}

// Type-safe visitor
public interface WikiEventVisitor<T> {
    T visitPageSave(PageSaveEvent event);
    T visitPageDelete(PageDeleteEvent event);
    // ...
}

// Type-safe listener
public interface PageEventListener {
    void onPageSave(PageSaveEvent event);
    void onPageDelete(PageDeleteEvent event);
}
```

### Benefits

- Compile-time type safety
- Exhaustive switch checking with sealed classes
- Better IDE support
- Immutable events with records

**Effort:** High | **Impact:** Medium (modernization)

---

## Priority 6: Template Method for Provider Lifecycle

### Current State

All providers implement `initialize()` but lifecycle is inconsistent:

```java
public interface WikiProvider {
    void initialize(Engine engine, Properties properties) throws ...;
    String getProviderInfo();
}
```

### Problem

- No standard shutdown/cleanup method
- No reload capability
- Providers manage their own initialization state inconsistently

### Recommended Pattern: Template Method with Lifecycle Hooks

```java
public abstract class AbstractWikiProvider implements WikiProvider {
    private Engine engine;
    private Properties properties;
    private boolean initialized;

    @Override
    public final void initialize(Engine engine, Properties properties)
            throws WikiException {
        this.engine = engine;
        this.properties = properties;

        validateConfiguration(properties);  // Hook
        doInitialize(engine, properties);   // Hook
        postInitialize();                   // Hook

        initialized = true;
    }

    public final void shutdown() {
        if (!initialized) return;
        doShutdown();        // Hook
        initialized = false;
    }

    public final void reload() throws WikiException {
        shutdown();
        initialize(engine, properties);
    }

    // Template methods for subclasses
    protected void validateConfiguration(Properties props) throws WikiException {}
    protected abstract void doInitialize(Engine engine, Properties props) throws WikiException;
    protected void postInitialize() {}
    protected void doShutdown() {}
}
```

### Benefits

- Consistent lifecycle across all providers
- Reloadable providers for configuration changes
- Clean shutdown for resource cleanup
- Validation hooks for early failure

**Effort:** Medium | **Impact:** Medium

---

## Implementation Roadmap

| Priority | Pattern | Effort | Impact | Dependencies |
|----------|---------|--------|--------|--------------|
| 1 | Decorator (Providers) | Medium | High | None |
| 2 | Abstract Factory (Storage) | Medium | High | None |
| 3 | Builder (Engine Init) | High | Medium | None |
| 4 | Strategy (Property Cache) | Low | Medium | None |
| 5 | Type-Safe Events | High | Medium | None |
| 6 | Template Method (Lifecycle) | Medium | Medium | None |

### Recommended Starting Point

**Priority 4 (Strategy for Property Cache)** - Low effort, builds on existing performance work, demonstrates pattern value.

### Quick Win Combination

Priority 1 + 4 together would significantly improve the provider architecture with moderate effort.

---

## Existing Well-Implemented Patterns

The codebase already has excellent implementations of several patterns:

- **Facade Pattern**: WikiEngine and Manager classes provide clean abstractions
- **Provider/Strategy Pattern**: PageProvider, AttachmentProvider, SearchProvider
- **Observer Pattern**: WikiEventManager with WikiEventListener
- **Chain of Responsibility**: FilterManager with PageFilter pipeline
- **Command Pattern**: Command interface with PageCommand, WikiCommand, etc.

These should be maintained and can serve as examples for new pattern implementations.
