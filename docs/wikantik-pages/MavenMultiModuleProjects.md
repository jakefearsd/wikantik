---
canonical_id: 01KQ0P44SASYCPSB9MXC9X3C89
title: Maven Multi Module Projects
type: article
tags:
- modul
- depend
- version
summary: This tutorial is not a "how-to" guide for junior developers.
auto-generated: true
---
# The Architecture of Scale

For those of us who spend our professional lives wrestling with build systems, the concept of the multi-module project is less a feature and more a necessary evil—a structural compromise required when an application’s complexity exceeds the scope of a single, monolithic artifact. If you are researching advanced techniques, you already understand that the goal is not merely to *build* multiple JARs, but to manage a complex, evolving *system* of interconnected components with predictable build semantics, robust dependency resolution, and maintainable release cycles.

This tutorial is not a "how-to" guide for junior developers. We assume proficiency with Maven's core concepts, including the Project Object Model (POM), dependency scopes, and the basic build lifecycle. Instead, we will dissect the multi-module structure at an architectural level, examining its inherent strengths, its subtle pitfalls, and how it interacts with modern deployment paradigms that often render the traditional Maven structure insufficient.

---

## 1. Foundational Theory: Deconstructing the Multi-Module Concept

At its heart, a Maven multi-module project is an organizational construct that leverages the parent POM to coordinate the build lifecycle across several distinct, yet related, sub-modules. The parent POM acts as the central orchestrator, defining common dependencies, plugin versions, and build profiles, while the sub-modules represent the discrete, cohesive units of functionality.

### 1.1 The Role of the Parent POM

The parent POM (`pom.xml` at the root) is arguably the most critical, and often most misunderstood, component. It does not compile code; it manages *metadata* and *build coordination*.

**Key Functions of the Parent:**

1.  **Dependency Management Aggregation:** It centralizes `<dependencyManagement>` blocks. This is crucial because it allows all child modules to inherit specific versions for common libraries (e.g., Spring Boot, Jackson, JUnit) without explicitly declaring the version in every single module's POM. This prevents version drift—a silent killer in large codebases.
2.  **Plugin Version Control:** Similarly, it manages plugin versions. If Module A and Module B both require the `maven-compiler-plugin`, the parent ensures they use the exact same, tested version, preventing runtime incompatibilities caused by mismatched build tooling.
3.  **Reactor Coordination:** The parent POM implicitly invokes the Maven Reactor. The Reactor is the mechanism that determines the correct build order. It analyzes the dependency graph defined by the `<modules>` section and executes the build lifecycle in a topologically sorted order (i.e., if Module B depends on Module A, Module A *must* be built and installed before Module B can be successfully compiled).

**Expert Insight: The Danger of Over-Reliance on `<dependencyManagement>`**

While powerful, over-relying on `<dependencyManagement>` can mask architectural debt. If a module *should* have a unique dependency version because it implements a specialized protocol or uses a bleeding-edge library, forcing it through the parent's management block can lead to subtle, hard-to-debug conflicts. The parent should enforce *standards*, not *absolute truth* for every single dependency.

### 1.2 Module Granularity and Cohesion

The decision of where to draw the boundary between modules is the single most important architectural decision. Poor module boundaries lead to "God Modules"—components that are too large, violating the Single Responsibility Principle (SRP) at the architectural level.

We categorize modules based on their functional role:

*   **Core/Domain Module (The Source of Truth):** Contains pure business logic, entities, and domain models. It should have *zero* external dependencies on frameworks (like Spring or Jakarta EE) and should ideally only depend on Java standard libraries or other core domain modules. This module must be the most stable and least frequently changed.
*   **Service/Application Module (The Orchestrator):** Implements the business workflows. It depends on the `Core/Domain` module and potentially on infrastructure modules (like persistence or messaging). This module coordinates calls between domain services.
*   **API/Interface Module (The Contract):** Often contains DTOs, REST resource definitions, or message payloads. Its sole purpose is to define the *contract* between other modules. It should ideally have no implementation logic whatsoever.
*   **Infrastructure/Persistence Module:** Handles cross-cutting concerns like database access (JPA repositories), external API clients, or message queue producers/consumers. It depends on the `Core/Domain` module to know *what* data it is persisting.
*   **Presentation/Web Module:** The outermost layer (e.g., `web-ui`, `rest-api`). It depends on the `Service` module and handles serialization, request mapping, and presentation concerns.

**The Dependency Flow Rule:** Dependencies must flow *inward* toward the core. The Web module depends on the Service module, which depends on the Domain module. The Domain module must never depend on the Web module. Violating this creates circular dependencies, which the Maven Reactor will fail to resolve gracefully.

---

## 2. Advanced Build Lifecycle Management and Reactor Control

For experts, the build process is not a linear sequence; it is a graph traversal problem. Understanding how Maven executes this graph is key to optimizing build times and managing complex release scenarios.

### 2.1 The Maven Reactor Execution

When you run `mvn clean install`, Maven does not simply compile everything. It builds the dependency graph and executes the lifecycle phases in the correct order.

**The Build Order:**
1.  The Reactor identifies all modules.
2.  It determines the build order based on declared dependencies.
3.  It executes the specified phase (e.g., `compile`, `test`, `package`) on the first module in the sequence.
4.  Upon successful completion, it moves to the next module, and so on, until all modules have successfully executed the phase.

**Optimization Technique: Selective Building**
In large projects, running `mvn clean install` is wasteful if only one module has changed. Experts must leverage targeted builds:

```bash
# Only compile and test the 'service-api' module, assuming it's the only one that changed.
mvn clean install -pl service-api -am
```
*   `-pl <module-id>`: Specifies the modules to process.
*   `-am` (or `--also-make`): Instructs Maven to build the specified modules *and* any modules they depend on, ensuring the necessary prerequisites are built first.

### 2.2 Plugin Management and Customization

The parent POM is the ideal place to enforce plugin standards, but sometimes, the standard lifecycle phases are insufficient.

**A. Customizing the Compiler Plugin:**
While the parent sets the version, you might need to enforce specific language features or target JVMs across all modules, even if they are written by different teams.

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>${maven.compiler.plugin.version}</version>
    <configuration>
        <source>${java.version}</source>
        <target>${java.version}</target>
        <compilerArgs>
            <arg>--enable-preview</arg> <!-- Example for preview features -->
        </compilerArgs>
    </configuration>
</plugin>
```

**B. Integrating Build-Time Checks (Validation Plugins):**
For advanced research, you might need to enforce architectural rules *during* the build, not just at runtime. This requires custom plugins or integrating tools like ArchUnit into the `test` phase.

*Pseudocode Concept:*
```xml
<build>
    <plugins>
        <plugin>
            <groupId>com.example</groupId>
            <artifactId>archunit-maven-plugin</artifactId>
            <executions>
                <execution>
                    <id>validate-architecture</id>
                    <phase>test</phase>
                    <goals>
                        <goal>validate</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```
This ensures that if a developer accidentally introduces a dependency violation (e.g., the Web module directly calling a private method in the Domain module), the build fails immediately, providing immediate feedback.

### 2.3 Handling Packaging Diversity (WAR, EAR, JAR, etc.)

A multi-module project often needs to produce artifacts of wildly different types. The parent POM must accommodate this diversity without creating conflicts.

*   **JAR (Library):** The default, used for reusable components (Domain, Service).
*   **WAR (Web Application):** Used for traditional Servlet-based frontends. Requires the `maven-war-plugin`.
*   **EAR (Enterprise Archive):** Used for deploying multiple related WAR/JARs together in a single container (e.g., JBoss/WildFly). Requires careful coordination of the `maven-ear-plugin`.

**The EAR Complexity:**
When using EARs, the parent POM must manage the coordinates for the parent artifact *and* the structure of the contained modules. The EAR plugin must be configured to correctly assemble the final archive, ensuring that the classpath entries for all contained modules are correctly resolved and bundled. This is a prime area for versioning errors, as the parent must know the *final* version of every component it bundles.

---

## 3. Dependency Graph Management: The Art of Mediation

Dependency management is where most multi-module projects stumble. It’s not just about listing dependencies; it’s about controlling *which version* of a dependency is visible to *which module* at *which time*.

### 3.1 The Problem of Transitivity and Scope

Maven's dependency resolution is inherently transitive. If Module A depends on Library X (version 1.0), and Module B depends on Library Y (which transitively depends on Library X, version 2.0), Maven must resolve this conflict.

*   **The Conflict Resolution Mechanism:** Maven typically favors the *nearest* definition in the dependency tree, but this behavior can be unpredictable when multiple paths lead to the same artifact.
*   **The Solution: Explicit Overriding:** In the parent POM, if you detect a conflict (e.g., two modules pulling in different versions of Guava), you must use `<dependencyManagement>` to explicitly declare the single, canonical version for that artifact, forcing all modules to adhere to it.

### 3.2 Utilizing Bill of Materials (BOM)

For modern, complex ecosystems (especially those involving multiple vendor libraries like Spring Boot, Hibernate, etc.), the concept of a BOM is superior to simple `<dependencyManagement>`.

A BOM (often implemented as a special type of POM) is designed *only* to manage versions. It declares a set of coordinates and versions without providing any actual dependencies that need to be compiled or packaged.

**Practical Application:**
Instead of listing every single dependency version in the parent POM, you import the vendor's BOM:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>${spring-boot.version}</version>
            <type>pom</type>
            <scope>import</scope> <!-- Crucial: Tells Maven this is a BOM -->
        </dependency>
    </dependencies>
</dependencyManagement>
```
This pattern delegates the version control authority to the library vendor, which is significantly more robust than maintaining a massive, manually curated list of versions in your own parent POM.

### 3.3 Managing Inter-Module Dependencies (The `provided` Scope Misconception)

A common mistake is misusing the `<scope>provided</scope>`.

**Misconception:** Developers often use `provided` scope when Module B depends on Module A, believing it means "Module A will be provided by the runtime environment."
**Reality:** The `provided` scope tells Maven: "This dependency is needed for compilation, but it will be supplied by the container (e.g., Servlet API in a WAR, or the runtime classpath)."

When Module B depends on Module A, you *must* declare the dependency normally (no scope) in Module B's POM. The parent POM's role is to ensure that Module A is built and installed into the local repository *before* Module B attempts to compile against it. The scope only dictates what the *final* packaging mechanism assumes about the dependency, not the build order.

---

## 4. Architectural Pitfalls and Edge Case Handling

Experts are defined by how well they anticipate failure. Here we address the structural weaknesses and integration headaches inherent in large multi-module setups.

### 4.1 The Problem of the "Fat JAR" vs. Modularization

In the era of microservices and containerization, the traditional goal of creating a single, deployable WAR or EAR artifact is often obsolete.

**The Shift in Paradigm:**
The modern goal is often to produce a set of self-contained, executable JARs (often called "fat JARs" or "executable JARs") that can be run directly via `java -jar`.

*   **Maven Implementation:** This requires plugins like the `maven-shade-plugin` or `maven-assembly-plugin`.
*   **The Challenge:** When using these plugins, you are effectively *re-implementing* the function of a container runtime. You must meticulously manage which dependencies are bundled into the final JAR (the application code) and which are left on the classpath (the runtime environment).
*   **Best Practice:** If you are targeting containers (Docker/Kubernetes), the best practice is to structure your modules to produce clean, dependency-minimal JARs. The container runtime (e.g., Spring Boot's embedded Tomcat) handles the classpath assembly, making the build process cleaner and the resulting artifact smaller and more portable than a massive, self-contained WAR.

### 4.2 Version Skew and Semantic Versioning Enforcement

In a multi-module system, versioning must be rigorously enforced using Semantic Versioning (SemVer: MAJOR.MINOR.PATCH).

*   **MAJOR Version Bump:** Indicates an incompatible API change (e.g., renaming a core domain field). This *must* trigger a coordinated release across all dependent modules.
*   **MINOR Version Bump:** Indicates adding functionality in a backward-compatible way (e.g., adding a new optional endpoint).
*   **PATCH Version Bump:** Indicates only bug fixes.

**The Release Plugin Dilemma (Addressing Source [1]):**
The Stack Overflow discussion regarding the Maven Release Plugin highlights a critical operational constraint: **it forces synchronous release.** If Module A is ready for v2.0, but Module B (which depends on A) is only ready for v1.9, the standard release process stalls.

**Advanced Mitigation Strategy: Decoupling Release from Build:**
Instead of relying on a monolithic release plugin, adopt a strategy where:
1.  The build system (Maven) is used only for *local integration testing* (`mvn clean verify`).
2.  The versioning is managed by a dedicated service (e.g., Git tags, or a central version catalog service).
3.  The deployment pipeline (CI/CD) is responsible for *coordinating* the release. It checks the readiness of all modules against the required version bump and only proceeds if all dependencies are satisfied for the target version.

### 4.3 Testing Strategy Across Boundaries

Testing in a multi-module environment is exponentially harder than in a single module. You cannot simply run `mvn test` and assume everything is covered.

**The Testing Pyramid in Multi-Module Context:**

1.  **Unit Tests (Module Level):** Must be contained entirely within the module being tested. They should *never* mock dependencies on other modules; they should test the logic in isolation.
2.  **Integration Tests (Module Pair Level):** Test the interaction between two adjacent modules (e.g., Service $\leftrightarrow$ Repository). These tests often require mocking the *external* boundaries (like the database connection or the message queue) but must use the *actual* implementation of the dependency module.
3.  **System Tests (End-to-End):** These are the most brittle. They should be run against the fully assembled artifact (e.g., the WAR deployed to a test container) and should ideally be orchestrated by a dedicated testing framework (like Testcontainers) rather than relying solely on Maven's build lifecycle.

**The Importance of Test Scope:**
Be extremely careful with `<scope>test</scope>`. If Module B tests its interaction with Module A, and Module A's test dependencies are complex, you must ensure that the test execution environment for Module B has access to the *compiled output* of Module A's test dependencies, which can be tricky to configure correctly in the parent POM.

---

## 5. Beyond Maven: Modern Build Tooling and Architectural Evolution

For researchers looking at "new techniques," dwelling solely on Maven's historical strengths is insufficient. The industry is moving toward build systems that treat the project graph as a first-class, dynamic citizen, rather than a static XML configuration.

### 5.1 The Gradle Paradigm Shift

Gradle is the most direct competitor and the most significant evolution point to study. Where Maven is declarative (you declare *what* you want), Gradle is programmatic (you write *how* to achieve it using a Domain Specific Language, or DSL, based on Groovy/Kotlin).

**Key Advantages of Gradle for Multi-Module:**

1.  **Custom Task Graph Manipulation:** Gradle allows you to write custom tasks that execute *between* standard lifecycle phases. If you need to run a proprietary code generation step that must happen *after* compilation but *before* packaging, Gradle makes this trivial to hook into the graph.
2.  **Convention Plugins:** Gradle excels at defining "conventions." You can write a single Kotlin script that defines the entire build contract for an "API Module" (e.g., "Every API module must have this plugin applied, must use this version of Jackson, and must run this specific validation task"). This is far more flexible than Maven's reliance on XML inheritance.
3.  **Version Catalogs:** Modern Gradle versions support Version Catalogs, which provide a centralized, type-safe mechanism for managing versions across hundreds of modules, significantly reducing the boilerplate and risk associated with large parent POMs.

**Expert Takeaway:** If your project structure requires complex, conditional build logic (e.g., "If the target environment is 'staging', use this specific database driver version; otherwise, use the default"), Gradle's programmatic nature will save you weeks of XML wrestling.

### 5.2 Containerization and Buildpacks: The Ultimate Decoupling

The most significant architectural shift is the move away from the build tool managing the *runtime environment*.

**The Buildpack Concept:**
A Buildpack (e.g., Spring Boot Buildpacks, Cloud Native Buildpacks) takes a set of compiled artifacts (your JARs) and wraps them, along with the necessary runtime dependencies, into a standardized, immutable OCI image.

**How this changes the Maven role:**
1.  **Maven's Role:** Becomes purely responsible for compiling and packaging the *application code* into a minimal, dependency-clean JAR (the "payload").
2.  **The Build Tool's Role:** The CI/CD pipeline invokes the Buildpack tool (e.g., `buildpacks/gradle` or a dedicated Docker build step).
3.  **The Result:** The resulting image contains the application code *plus* the entire necessary runtime environment (JVM, OS libraries, etc.), eliminating the need for the build tool to worry about classpath assembly for deployment.

This decoupling is the zenith of modern architectural design for large systems, as it separates the concerns of **Compilation/Packaging** (Maven/Gradle) from **Runtime Environment Provisioning** (Container Orchestration).

### 5.3 Advanced Dependency Resolution: Maven Enforcer Plugin

For the expert who needs absolute control, the `maven-enforcer-plugin` is indispensable. It allows you to write custom rules that fail the build based on structural violations, not just compilation errors.

**Use Cases for Advanced Enforcement:**

*   **Dependency Exclusion Enforcement:** Ensuring that no module accidentally pulls in a transitive dependency that is known to be insecure or incompatible.
*   **Dependency Version Range Checking:** Forcing all modules to use versions within a specific range (e.g., "All logging frameworks must be between 1.x and 2.x").
*   **Module Dependency Check:** Enforcing the dependency flow rule programmatically. You can write a rule that checks the `pom.xml` of every module and throws an error if it finds a dependency on a module that is *not* listed in the `<modules>` section of the parent.

---

## Conclusion: Synthesis and Final Architectural Directives

The Maven multi-module structure is a powerful, time-tested pattern for managing complexity. However, viewing it as a static solution is a mistake. It is a *framework* that must be adapted to the evolving needs of the system.

For the expert researching new techniques, the key takeaways are not about writing the XML, but about understanding the *boundaries* and the *intent* behind the build process:

1.  **Prioritize Contract Over Implementation:** Design modules around stable, versioned contracts (APIs/DTOs) rather than around shared implementation details.
2.  **Embrace the Build Graph:** Treat the dependency graph as a first-class citizen. Use the Reactor's capabilities (or migrate to Gradle's programmatic control) to manage build order explicitly.
3.  **Decouple Deployment:** Recognize that the goal of the build system should increasingly be to produce the *smallest, purest payload* possible, leaving the responsibility of runtime assembly and environment provisioning to containerization tools.
4.  **Version Control the Process:** Treat your versioning strategy (SemVer, BOMs, Release Coordination) with the same rigor as your core business logic.

In essence, a master multi-module architect does not just know how to write the parent POM; they know when the parent POM is becoming a bottleneck, and when it is time to transition the orchestration logic to a more flexible, programmatic build system or, ideally, to the orchestration layer of the deployment environment itself.

If you follow these principles—moving from mere structure management to deep architectural governance—you will build systems that are not just compilable, but truly resilient to the inevitable entropy of large-scale software development.
