---
cluster: software-engineering-practices
canonical_id: 01KQ0P44W7VKS643JKBG1KP4X2
title: Semantic Versioning
type: article
tags:
- semver
- versioning
- ci-cd
- automation
summary: Deep dive into the SemVer 2.0.0 specification and its integration into automated CI/CD pipelines.
auto-generated: false
date: 2025-05-15
---

# Semantic Versioning (SemVer) 2.0.0

Semantic Versioning is a formal specification for software versioning that communicates the nature of changes through a three-part version number. It is the industry standard for managing dependency contracts.

## 1. The SemVer Grammar

The version format is defined as `MAJOR.MINOR.PATCH`, optionally followed by pre-release or build metadata.

*   **MAJOR:** Incremented for incompatible API changes (Breaking changes).
*   **MINOR:** Incremented for backward-compatible functionality additions.
*   **PATCH:** Incremented for backward-compatible bug fixes.

### 1.1 Pre-release and Build Metadata
*   **Pre-release:** Appended with a hyphen (e.g., `1.0.0-alpha.1`). These have lower precedence than the associated normal version.
*   **Build Metadata:** Appended with a plus sign (e.g., `1.0.0+20130313144700`). This is ignored when determining version precedence.

## 2. CI/CD Automation Patterns

Manually updating version numbers is error-prone. Modern pipelines automate this using **Conventional Commits**.

### 2.1 The Conventional Commits Pattern
By parsing commit messages, a CI tool can determine the correct version bump:
*   `fix: ...` $\rightarrow$ **PATCH**
*   `feat: ...` $\rightarrow$ **MINOR**
*   `feat!: ...` or `BREAKING CHANGE: ...` $\rightarrow$ **MAJOR**

### 2.2 Automation Workflow
1.  **Commit:** Developers follow Conventional Commits.
2.  **Analysis:** The CI pipeline (e.g., Semantic Release) analyzes commits since the last tag.
3.  **Bumping:** The tool calculates the new version, updates the manifest (e.g., `package.json`, `pom.xml`), and creates a Git tag.
4.  **Changelog:** A `CHANGELOG.md` is automatically generated from the commit history.

## 3. Practitioner Insights

### 3.1 The "v0.x" Exception
Major version zero (`0.y.z`) is for initial development. Anything may change at any time. The public API should not be considered stable until `1.0.0`.

### 3.2 Post-1.0.0 Rigor
Once you hit `1.0.0`, any breaking change—even if it seems "minor" or "obvious"—requires a MAJOR version bump. Violating this destroys trust in your dependency contract.

### 3.3 Dependency Ranges
Use pessimistic versioning in your consumers to balance stability and security:
*   `~1.2.3` (Tilde): Allows patch updates (`1.2.x`).
*   `^1.2.3` (Caret): Allows minor and patch updates (`1.x.x`).
