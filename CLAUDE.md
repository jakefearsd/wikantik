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

# Parallel build for faster execution
mvn clean install -T 1C

# Build without JavaScript/CSS compression
mvn clean install -Dmaven.test.skip -Dminimize=false

# Build portable distribution with native launchers
mvn clean install -Dgenerate-native-launchers=true   # from jspwiki-portable module
```

### Testing Commands
```bash
# Run all tests
mvn clean test

# Run a specific test class
mvn test -Dtest=JSPWikiMarkupParserTest

# Run a specific test method
mvn test -Dtest=JSPWikiMarkupParserTest#testHeadingHyperlinks3

# Debug a test
mvn test -Dtest=TestClassName#methodName -Dmaven.surefire.debug

# Run integration tests (from jspwiki-it-tests folder)
mvn clean install -Pintegration-tests

# Run memory profiling test (from jspwiki-main module)
mvn test -Dtest=MemoryProfiling
```

### Development Server
```bash
# Start JSPWiki on Tomcat at http://localhost:8080/JSPWiki with debugger on port 5005
# The tomcat server is under the tomcat/tomcat-11 directory, and you must use this instances for running and testing.
mvn org.codehaus.cargo:cargo-maven3-plugin:run   # from jspwiki-war module
```

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

JSPWiki is a modular Java-based wiki engine built on JEE technologies with the following key characteristics:

### Core Components

1. **WikiEngine** (`org.apache.wiki.WikiEngine`): Central orchestrator that manages all subsystems. Singleton per web application that provides access to all manager instances.

2. **WikiContext** (`org.apache.wiki.WikiContext`): Request-scoped context object that holds current page, user session, and request state. Essential for any operation that needs context about the current request.

3. **WikiSession** (`org.apache.wiki.WikiSession`): Manages user authentication state and principals. Integrates with JAAS for security.

4. **Manager Classes**: Each major subsystem has a manager interface with a default implementation:
   - `PageManager` - Page lifecycle and storage
   - `AttachmentManager` - File attachment handling
   - `PluginManager` - Plugin loading and execution
   - `FilterManager` - Content filtering pipeline
   - `SearchManager` - Search functionality
   - `RenderingManager` - Wiki markup rendering

### Module Structure

- **jspwiki-api**: Core interfaces and contracts
- **jspwiki-main**: Main implementation of wiki functionality
- **jspwiki-event**: Event system for decoupled communication
- **jspwiki-util**: Utility classes and helpers
- **jspwiki-bootstrap**: Initialization and bootstrap
- **jspwiki-cache**: EhCache-based caching layer
- **jspwiki-markdown**: Markdown syntax support
- **jspwiki-war**: WAR packaging for deployment

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

1. **Parser**: `JSPWikiMarkupParser` converts wiki text to DOM
2. **Filters**: Pre/post-processing of content
3. **Plugins**: Dynamic content insertion
4. **Renderer**: Final output generation (XHTML, etc.)

### Security Model

- JAAS-based authentication and authorization
- Fine-grained permissions (PagePermission, WikiPermission)
- ACL support for individual pages
- Pluggable authentication (LDAP, database, container)

### Workflow System

- Sophisticated workflow engine for content approval
- Step-based processing with human decision points
- Used for page saves, user registration, group management

### Important Configuration

- Main configuration: `ini/jspwiki.properties` (in JAR)
- Custom overrides: `jspwiki-custom.properties` (in WEB-INF or container lib)
- Security policy: `WEB-INF/jspwiki.policy`

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
- Test utilities in `org.apache.wiki.TestEngine`
- Mock implementations available for most components
