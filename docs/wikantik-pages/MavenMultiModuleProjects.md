---
canonical_id: 01KQ0P44SASYCPSB9MXC9X3C89
title: Maven Multi-Module Projects
type: article
cluster: java
status: active
date: '2026-04-26'
summary: How to structure a Maven multi-module project — parent POM patterns, BOM,
  dependency management vs. direct dependencies, and the conventions that scale to
  large codebases.
tags:
- maven
- multi-module
- java
- build-tools
- dependency-management
related:
- JavaBuildToolComparison
- JavaModuleSystem
- SpringBootFundamentals
- JpaAndHibernatePatterns
hubs:
- Java Hub
---
# Maven Multi-Module Projects

A Maven multi-module project is a parent POM with multiple child modules under it. The pattern is the standard for structuring Java applications larger than a single artifact. Done well, it provides clear module boundaries, centralized version management, and reusable configuration. Done badly, it produces tangled inheritance, inconsistent versions, and slow builds.

This page covers the patterns that work and the conventions to avoid.

## The basic structure

```
my-project/
├── pom.xml                  (parent / aggregator)
├── my-project-api/
│   └── pom.xml              (interfaces and types)
├── my-project-impl/
│   └── pom.xml              (implementation)
├── my-project-server/
│   └── pom.xml              (deployment artifact)
└── my-project-tests/
    └── pom.xml              (integration tests)
```

The parent POM has `<packaging>pom</packaging>` and declares the modules:

```xml
<modules>
    <module>my-project-api</module>
    <module>my-project-impl</module>
    <module>my-project-server</module>
    <module>my-project-tests</module>
</modules>
```

## Parent POM responsibilities

A well-designed parent POM:

1. Declares common properties (Java version, encoding)
2. Manages dependency versions via `<dependencyManagement>`
3. Manages plugin versions via `<pluginManagement>`
4. Configures shared plugins (compiler, surefire, etc.)
5. Lists modules for aggregation

It does NOT:
- Declare actual dependencies that all modules need (that bloats every module)
- Configure module-specific behavior
- Reference specific module names

## Dependency management vs. direct dependencies

The distinction matters:

```xml
<!-- In parent: dependencyManagement -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>shared-lib</artifactId>
            <version>1.2.3</version>
        </dependency>
    </dependencies>
</dependencyManagement>

<!-- In child: just declare, version inherited -->
<dependencies>
    <dependency>
        <groupId>com.example</groupId>
        <artifactId>shared-lib</artifactId>
    </dependency>
</dependencies>
```

`dependencyManagement` declares versions but does not add dependencies; child modules opt in by listing the dependency without a version. This pattern centralizes versions while allowing modules to choose which dependencies they actually need.

## BOM (Bill of Materials)

A BOM is a POM that exists only to declare dependency versions. Used for "import" the version map into other projects:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>3.2.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

This imports Spring Boot's BOM, which sets versions for hundreds of libraries Spring Boot is tested against. Your project then uses Spring Boot's versions automatically.

For your own project, a BOM module is the right pattern when external consumers need to depend on your project. They can import your BOM and get a tested set of versions.

## Inter-module dependencies

A module depending on another module in the same multi-module project:

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>my-project-api</artifactId>
    <version>${project.version}</version>
</dependency>
```

Use `${project.version}` so the version is automatically the same as the parent. Avoid hard-coding versions in inter-module dependencies.

## Common patterns

### API + Impl separation

```
my-project-api/      <- interfaces, DTOs, exceptions
my-project-impl/     <- implementation classes
```

Other modules depend on `my-project-api`. The implementation is private and pluggable. Useful for libraries that expose a stable API.

### Server module

A `my-project-server` (or `-app`, `-deploy`) module produces the deployable artifact (WAR, JAR with main class). Other modules are libraries.

This separation makes the deployment artifact clear; the server module's POM tends to be longer (Spring Boot config, packaging configuration) and it's good to isolate that.

### Test module

A `my-project-tests` (or `-it`, `-it-tests`) module for integration tests that span the whole application. Keeps slow tests separate from unit tests in individual modules.

For multi-module projects, this is often a Maven Failsafe-driven module that runs against the deployed server module.

## The build performance question

Multi-module builds can be slow at scale. Specific levers:

### Parallel builds

```bash
mvn clean install -T 1C
```

Builds modules in parallel. Speedup proportional to the number of independent modules.

Caveat: integration tests sharing fixed ports cannot run in parallel. See the JSPWiki/Wikantik convention of running integration tests serially.

### `-pl` and `-am`

Build only specific modules:

```bash
mvn install -pl my-project-impl -am
```

`-pl` selects the module; `-am` ("also-make") includes its dependencies. Useful for fast iteration during development.

### Incremental builds

Maven's incremental support is limited. For real incremental needs, Gradle is better; or use `-pl` selectively.

### Skip tests during development

```bash
mvn install -DskipTests
```

For iteration when you've already verified test results elsewhere.

## Patterns to avoid

### Deep inheritance

Parent → child → grandchild → great-grandchild. Each level adds complexity for little benefit. Two levels (parent + module children) is plenty for most projects.

### Module-specific declarations in parent

If you find yourself adding `<dependency>` to the parent that only one module needs, move it to the module. Parent should be common ground, not sprawl.

### Hard-coded versions in modules

Versions belong in `dependencyManagement` (parent or BOM). Each module just declares which dependencies it uses.

### Forgetting `<scope>provided</scope>`

For dependencies that are provided by the runtime (servlet API, Spring Boot's auto-configured beans), use `<scope>provided</scope>` to avoid bundling them.

### Dynamic versions

`<version>1.+</version>` or `LATEST` produce non-reproducible builds. Always use specific versions.

## Common failure patterns

- **Tangled cross-module dependencies.** A depends on B which depends on A's helper, etc. Often signals bad design at a higher level.
- **Inconsistent versions.** Multiple versions of the same library — the dependency tree shows duplicates. Use `dependencyManagement`.
- **Heavy parent POM.** Parent doing too much; child modules can't be built independently.
- **No clear module boundaries.** "Module" in name only; everything depends on everything.
- **Version-controlled IDE files.** `.idea/`, `.iml` files, etc. Use `.gitignore`.

## Further Reading

- [JavaBuildToolComparison](JavaBuildToolComparison) — Maven vs. Gradle vs. Bazel
- [JavaModuleSystem](JavaModuleSystem) — JPMS modules (different from Maven modules)
- [SpringBootFundamentals](SpringBootFundamentals) — Spring Boot's BOM and starter parents
- [JpaAndHibernatePatterns](JpaAndHibernatePatterns) — A common Maven dependency
- [Java Hub](Java+Hub) — Cluster index
