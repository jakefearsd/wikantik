---
canonical_id: 01KQ0P44X3SC9XBFJRV9TPWHG7
title: Supply Chain Security
type: article
cluster: security
status: active
date: '2026-04-26'
summary: How to defend against supply chain attacks — dependency vulnerabilities,
  malicious packages, build pipeline compromise — and the modern tools (SBOM, signing,
  SLSA) that establish provenance.
tags:
- supply-chain-security
- sbom
- slsa
- dependencies
- security
related:
- VulnerabilityManagement
- CiCdPipelines
- CloudSecurityFundamentals
---
# Supply Chain Security

Software supply chain attacks: instead of attacking the target directly, attack what they depend on — npm packages, pip libraries, container base images, build tools, CI/CD pipelines.

The category has exploded. SolarWinds, Log4Shell, npm package compromises — these are mainstream news. Defending against them requires deliberate work.

## The attack categories

### Compromised dependencies

A library you depend on gets a malicious update. Your build pulls it; your software ships with it.

Examples:
- Typosquatting (name similar to legitimate package)
- Account compromise (legitimate maintainer's account hacked)
- Malicious maintainer takeover (project transferred to bad actor)

### Compromised build pipeline

Attacker gets into CI/CD; modifies builds before they're signed/distributed.

The SolarWinds attack: build pipeline modified; legitimate-signed binaries had backdoor.

### Compromised distribution

Even legitimately-built software can be replaced in distribution. CDN compromise; mirror site replacement.

### Compromised infrastructure

Cloud account compromise; container registry compromise. Software runs on infrastructure that's been altered.

## Defenses

### Dependency scanning

Continuously scan dependencies for known vulnerabilities. CVE database is updated; your scanner checks.

Tools:
- Dependabot (GitHub native)
- Snyk
- OWASP Dependency-Check
- AWS Inspector
- Trivy (containers)

Scan in CI; block deploys with critical CVEs. Triage and patch quickly.

### Pin versions

Don't use floating versions. `1.+` or `latest` means new versions enter without review.

Pin to specific versions; review updates before bumping.

### Lock files

`package-lock.json`, `yarn.lock`, `Pipfile.lock`, `Gemfile.lock`. Record exact versions of transitive dependencies. Reproducible builds; no surprise updates.

### Verify package provenance

Modern package registries support cryptographic signing. Verify:
- npm provenance (sigstore)
- PyPI Trusted Publishers
- Maven Central GPG signatures

The package you install is what the maintainer published.

### SBOM (Software Bill of Materials)

A list of everything in a build: every library, every transitive dep, every version, every license.

Standard formats: SPDX, CycloneDX.

Generated at build; published with releases. Customers can verify what's in the software.

### SLSA framework

Supply-chain Levels for Software Artifacts (pronounced "salsa"). Levels 1-4 of supply chain maturity.

- Level 1: documented build process
- Level 2: tamper resistance via signing
- Level 3: source/build authenticated
- Level 4: hermetic builds with two-party review

For most organizations, Level 2-3 is the realistic target.

### Internal package mirrors

For sensitive enterprises, mirror package registries internally. Vet packages before they enter the mirror.

Slows velocity; adds security. Trade-off worth it for some.

### Container image scanning

Container images contain layers from base images. Each layer can have vulnerabilities.

Scan images:
- Build-time: in CI
- Runtime: continuously check running containers

Tools: Trivy, Grype, Snyk, ECR scanning, GCR vulnerability scanning.

### Keep base images current

The "FROM ubuntu:22.04" base image gets updates. Pull regularly; rebuild; redeploy. Otherwise vulnerabilities in the base accumulate.

Automate base image updates where feasible.

### Reproducible builds

Same source + same dependencies = bit-identical output. Detects build-pipeline tampering.

Bazel and similar tools support this. Most casual builds aren't reproducible.

### CI/CD security

The build pipeline is high-value. Protect it:
- Limit who can modify pipeline configuration
- Audit pipeline changes
- Separate build environments from production
- Sign artifacts in CI; verify on deploy
- No long-lived secrets in CI; use OIDC for cloud authentication

## Specific patterns

### Pull from trusted sources

Verify package source. Don't pull from arbitrary URLs.

### Vet new dependencies

Before adding a dependency, evaluate:
- Maintenance status
- Security history
- License
- Number of contributors
- Audit if security-sensitive

The cost of vetting is small; the cost of compromise is large.

### Continuous vulnerability monitoring

Vulnerabilities discovered after release. Monitoring catches them; patching closes them.

Critical CVEs deserve fast response. Have an incident process for "Log4Shell-class" disclosures.

### Air-gapped or hermetic builds

For high-security environments, builds run without internet access. All dependencies pre-mirrored. No surprise downloads.

## Common failure patterns

- **No dependency scanning.** Vulnerabilities ride along.
- **Floating versions.** New versions enter without review.
- **No lock files.** Transitive dependencies vary across builds.
- **No CI security.** Build pipeline compromise goes undetected.
- **Slow patching.** Known critical CVE in deployed software for weeks.
- **No SBOM.** Customers can't verify what they're running.
- **Untrusted dependencies.** Third-party packages without vetting.

## A reasonable starter

For most projects:

1. Lock files committed
2. Dependency scanning (Dependabot or similar) enabled
3. Auto-merge minor security updates after CI passes
4. SBOM generated at release
5. Container images scanned
6. CI/CD secrets managed properly (OIDC; not long-lived keys)

Beyond this, signing, attestation, and reproducible builds for higher-stakes software.

## Further Reading

- [VulnerabilityManagement](VulnerabilityManagement) — Adjacent practice
- [CiCdPipelines](CiCdPipelines) — CI security
- [CloudSecurityFundamentals](CloudSecurityFundamentals) — Cloud-specific
