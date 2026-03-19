# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
The preferred development apppoach is test driven development, with putting tests in place to show
defects before they are repaired, so that we know we have an effective test to detect the issue.

## Development Commands

### Build Commands
```bash
# Standard build (includes tests and Apache RAT check)
mvn clean install

# Build without tests (faster, but not recommended for final checks)
mvn clean install -Dmaven.test.skip

# Parallel build for faster execution (unit tests only, NOT integration tests)
mvn clean install -T 1C -DskipITs

# Build without JavaScript/CSS compression
mvn clean install -Dmaven.test.skip -Dminimize=false
```

### Testing Commands
```bash
# Run all tests
mvn clean test

# Run a specific test class
mvn test -Dtest=WikantikMarkupParserTest

# Run a specific test method
mvn test -Dtest=WikantikMarkupParserTest#testHeadingHyperlinks3

# Debug a test
mvn test -Dtest=TestClassName#methodName -Dmaven.surefire.debug

# Run integration tests (MUST run without parallelism - see critical note below)
# Always use -fae (fail at end) so all 5 IT modules run even if one has failures
mvn clean install -Pintegration-tests -fae

# Run memory profiling test (from wikantik-main module)
mvn test -Dtest=MemoryProfiling
```

### Local Deployment (Tomcat 11)

The local Tomcat instance lives at `tomcat/tomcat-11`. Use this for running and testing — do not use Cargo.

The wiki is deployed as the ROOT context, serving pages from `docs/wikantik-pages/` (version-controlled).
Configuration files:
- `tomcat/tomcat-11/lib/wikantik-custom.properties` — wiki settings (page provider, base URL, PostgreSQL JDBC, etc.)
- `tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml` — Tomcat context with PostgreSQL JNDI DataSources

```bash
# 1. Build the WAR (skip tests for speed)
mvn clean install -Dmaven.test.skip -T 1C

# 2. Deploy as ROOT to local Tomcat
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war

# 3. Start Tomcat
tomcat/tomcat-11/bin/startup.sh

# 4. Access at http://localhost:8080/

# Stop Tomcat
tomcat/tomcat-11/bin/shutdown.sh
```

To redeploy after code changes, stop Tomcat, remove the extracted `webapps/ROOT/` directory, rebuild the WAR, copy it as `ROOT.war`, and start again.

### Code Quality
```bash
# Apache RAT license check
mvn apache-rat:check

# Generate Javadocs with UML diagrams
mvn javadoc:javadoc
```
High test coverage at the line level, above 90% is a goal for this development team,
and while we recognize it is no a perfect measurement, it is one we choose to pursue.
### Web Resource Management
```bash
# Merge and compress JavaScript/CSS files
mvn wro4j:run -Dminimize=true

# Only merge JavaScript/CSS files (no compression)
mvn wro4j:run -Dminimize=false
```

## Architecture Overview

Wikantik is a modular Java-based wiki engine built on JEE technologies with the following key characteristics:

### Core Components

1. **WikiEngine** (`com.wikantik.WikiEngine`): Central orchestrator that manages all subsystems. Singleton per web application that provides access to all manager instances.

2. **WikiContext** (`com.wikantik.WikiContext`): Request-scoped context object that holds current page, user session, and request state. Essential for any operation that needs context about the current request.

3. **WikiSession** (`com.wikantik.WikiSession`): Manages user authentication state and principals. Integrates with JAAS for security.

4. **Manager Classes**: Each major subsystem has a manager interface with a default implementation:
   - `PageManager` - Page lifecycle and storage
   - `AttachmentManager` - File attachment handling
   - `PluginManager` - Plugin loading and execution
   - `FilterManager` - Content filtering pipeline
   - `SearchManager` - Search functionality
   - `RenderingManager` - Wiki markup rendering

### Module Structure

- **wikantik-api**: Core interfaces and contracts
- **wikantik-main**: Main implementation of wiki functionality
- **wikantik-event**: Event system for decoupled communication
- **wikantik-util**: Utility classes and helpers
- **wikantik-bootstrap**: Initialization and bootstrap
- **wikantik-cache**: EhCache-based caching layer
- **wikantik-markdown**: Markdown syntax support
- **wikantik-mcp**: MCP server for AI agent integration
- **wikantik-war**: WAR packaging for deployment

### Key Design Patterns

1. **Provider Pattern**: Storage abstraction through provider interfaces
   - `PageProvider` for page storage (FileSystem, Versioning)
   - `AttachmentProvider` for attachment storage
   - `SearchProvider` for pluggable search engines

2. **Event-Driven Architecture**: WikiEvent system enables loose coupling
   - Components communicate through events
   - Listeners can react to page changes, user actions, etc.

3. **Command Pattern**: UI actions modeled as commands
   - URL-to-command mapping via CommandResolver
   - Clean separation of UI actions from business logic

4. **Plugin Architecture**: Extensible through multiple mechanisms
   - Plugins for dynamic content generation
   - Filters for content pre/post-processing
   - Custom editors, templates, and providers

### Rendering Pipeline

1. **Parser**: `MarkdownParser` converts Markdown to Flexmark AST (default). Legacy `WikantikMarkupParser` handles `.txt` pages with `markup.syntax=jspwiki`.
2. **Filters**: Pre/post-processing of content
3. **Plugins**: Dynamic content insertion via `[{Plugin}]` syntax (auto-normalized to `[{Plugin}]()` for Flexmark)
4. **Renderer**: `MarkdownRenderer` produces HTML via Flexmark (default). Legacy `XHTMLRenderer` for wiki-syntax documents.

### Security Model

- JAAS-based authentication and authorization
- Fine-grained permissions (PagePermission, WikiPermission)
- ACL support for individual pages
- Pluggable authentication (LDAP, database, container)

### Important Configuration

- Main configuration: `ini/wikantik.properties` (in JAR)
- Custom overrides: `wikantik-custom.properties` (in WEB-INF or container lib)
- Security policy: `WEB-INF/wikantik.policy`

### Extension Points

When implementing new features, consider these extension mechanisms:
- **Plugins**: For new wiki markup tags
- **Filters**: For content processing
- **Providers**: For custom storage backends
- **Modules**: For larger feature additions
- **Templates**: For UI customization

### Testing Approach

- Unit tests use JUnit 5
- Integration tests use Selenide for browser automation
- Test utilities in `com.wikantik.TestEngine`
- Mock implementations available for most components

### Critical: Integration Test Parallelism

**NEVER run integration tests with Maven parallel builds (`-T 1C` or `-T` flags).**

The integration tests use Maven Cargo to start embedded Tomcat instances that share fixed
port numbers (8080, 8205, etc.). Running multiple IT modules in parallel causes port
conflicts and unreliable test failures like:

```
Port number 8205 (defined with the property cargo.rmi.port) is in use
```

**Correct usage:**
```bash
# Integration tests - MUST be sequential (no -T flag), always use -fae
mvn clean install -Pintegration-tests -fae

# Unit tests only - can use parallel builds
mvn clean install -T 1C -DskipITs
```

Test suite reliability is critical for this project's development workflow.
