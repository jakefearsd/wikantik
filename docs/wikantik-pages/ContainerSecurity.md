---
title: Container Security
type: article
cluster: security
status: active
date: '2026-04-25'
tags:
- container-security
- kubernetes
- docker
- supply-chain
- runtime-security
summary: Defending containerised workloads — image scanning, runtime security,
  network policies, supply chain — and the controls that distinguish a real
  posture from a checkbox.
related:
- ContainerOrchestration
- ApplicationSecurityFundamentals
- ThreatModeling
- ZeroTrustArchitecture
hubs:
- Security Hub
---
# Container Security

Containerising your application doesn't make it secure. Containers add isolation but introduce a new attack surface — image registries, container runtimes, orchestrators, supply chains. The 2026 threat landscape includes supply-chain attacks (the SolarWinds template), runtime escapes, misconfigured K8s exposures, and credential theft through container metadata services.

This page is the working defence in depth.

## The threat layers

| Layer | Threat | Defence |
|---|---|---|
| **Image** | Vulnerable dependencies; embedded secrets | Image scanning; minimal base images; secret hygiene |
| **Registry** | Tampered images; unauthorised pulls | Signed images; private registry; access controls |
| **Build pipeline** | Compromised CI; malicious dependencies | SBOM; provenance attestations; pipeline isolation |
| **Runtime (container)** | Escape; privilege escalation; lateral movement | Non-root users; read-only filesystem; seccomp/AppArmor |
| **Runtime (orchestrator)** | Misconfigured RBAC; pod-to-pod attacks | Network policies; PSA; admission controllers |
| **Network** | Lateral movement; data exfiltration | mTLS; egress controls; service mesh policies |

Most teams do half of these well and neglect the other half. Each gap is exploited regularly.

## Image security

### Minimal base images

Smaller images = fewer vulnerabilities + fewer tools for attackers.

- **Distroless** (Google) — base image with only the runtime your app needs. No shell, no package manager.
- **Alpine** — small Linux distribution; popular base.
- **Scratch** — for static binaries (Go, Rust) — image is just your binary.

A Python app on `python:3.11-slim` (~80MB) has dozens of CVEs at any time. The same app on `python:3.11-slim-distroless` has fewer because there are fewer packages.

### Image scanning

Scan images for known vulnerabilities at build time and on registry push.

Tools:
- **Trivy** — open source; widely used; fast.
- **Grype** — Anchore's scanner; integrates with SBOM.
- **Snyk Container** — commercial; deeper analysis.
- **AWS Inspector / Google Container Analysis** — managed cloud scanners.

Scan on every build. Block deploys with critical CVEs in production-bound images.

### No embedded secrets

A surprising number of images ship with API keys, database passwords, SSH keys baked into layers. Tools (`detect-secrets`, `gitleaks`, `trufflehog`) scan for this.

The fix: secrets at runtime via environment variables, mounted volumes, or secret managers (AWS Secrets Manager, HashiCorp Vault, Kubernetes Secrets). Never in the image.

### SBOM (Software Bill of Materials)

A list of every component in the image. Enables vulnerability tracking, license compliance, supply-chain analysis.

Standards: SPDX, CycloneDX. Tools: Syft, ScribeSecurity, Microsoft SBOM Tool.

Generate at build; attach to the image; consumed by scanners and policy engines.

## Supply-chain security

The 2020-2024 wave of supply-chain attacks (SolarWinds, npm packages, PyPI typosquatting, GitHub Actions compromise) made supply-chain security a first-class concern.

### Pin dependencies

Don't `pip install requests` in your Dockerfile (latest version, could change anytime). Pin: `pip install requests==2.32.4`. Use lockfiles (`requirements.txt`, `package-lock.json`, `go.sum`, `Cargo.lock`).

### Dependency review

Tools that examine new dependencies for known issues:
- **GitHub Dependency Review action** — flags risky deps in PRs.
- **Snyk Open Source** — commercial.
- **Socket.dev** — analyses package behaviour, not just CVEs.

Review dependencies before adding. The `left-pad` style "we depend on this 12-line library" is also a supply-chain risk.

### Image signing and verification

Sign images at build with Sigstore (Cosign), Notary, or registry-native signing. Verify at deploy.

```
# Build and sign
cosign sign --key cosign.key registry.example/app:v1.2.3

# Verify before deploy
cosign verify --key cosign.pub registry.example/app:v1.2.3
```

Kubernetes admission controllers (Kyverno, OPA Gatekeeper) can enforce: only deploy signed images.

### Provenance attestations

SLSA (Supply-chain Levels for Software Artifacts) framework. Attestations prove "this image was built by this CI on this commit." Verifiable; tamper-evident.

In 2026, SLSA Level 3 is achievable with mainstream CI (GitHub Actions, GitLab CI). Adopt for production-bound builds.

## Runtime security

### Non-root user

Don't run containers as root. Even if the container escapes, root inside means root outside (in some configurations).

```dockerfile
FROM alpine
RUN adduser -D appuser
USER appuser
```

Most images run as root by default. Audit; fix.

### Read-only root filesystem

Containers don't need to write to most paths. Mark them read-only:

```yaml
securityContext:
  readOnlyRootFilesystem: true
```

Limits an attacker's ability to drop persistence. Mount specific writable volumes for legitimate writes.

### Drop capabilities

Linux capabilities give fine-grained privileges. Containers usually don't need most of them. Drop all and add back only what's required:

```yaml
securityContext:
  capabilities:
    drop: ["ALL"]
    add: ["NET_BIND_SERVICE"]
```

### Seccomp / AppArmor / SELinux

Kernel-level syscall filtering. Restricts what the container can ask the kernel to do.

- Kubernetes: `securityContext.seccompProfile.type: RuntimeDefault` is a sane default; tighten further if possible.
- Docker: `--security-opt seccomp=profile.json`.

Most workloads tolerate the default seccomp profile; few break things.

### Pod Security Admission (PSA)

Kubernetes' built-in policy enforcer. Three modes:
- `privileged` — no restrictions (legacy).
- `baseline` — common-sense restrictions.
- `restricted` — strict; root prevented; capabilities dropped.

Set `restricted` on application namespaces; relax only for specific exemptions.

### Namespaces and cgroups

Linux primitives that isolate containers. Standard; mostly invisible. Misconfigurations (sharing PID namespace, mounting host filesystems) defeat isolation.

## Network security in Kubernetes

By default, every pod can talk to every other pod. This is an attack-graph nightmare.

### Network Policies

Kubernetes NetworkPolicy resources restrict pod-to-pod and pod-to-external traffic.

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: api-allow-frontend-only
spec:
  podSelector:
    matchLabels:
      app: api
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: frontend
      ports:
        - port: 8080
```

Default-deny + explicit allow is the right shape. Without these, lateral movement is easy.

### Service mesh

Istio, Linkerd, Cilium service mesh. Provide:

- Automatic mTLS between pods.
- Authorisation policies at the call level.
- Observability of pod-to-pod traffic.
- Egress controls.

For mature security postures, mTLS-by-default + service-mesh policy is the operational shape.

### Egress filtering

Prevent compromised pods from exfiltrating data or beaconing to C2 servers. Egress proxies (Cilium, ASM, Tailscale-style ZTNA) restrict outbound traffic to allowlisted destinations.

Most teams don't do this. The pods that compromised do it for you.

## Runtime threat detection

Detect attacks in progress:

- **Falco** — runtime behaviour rules; fires on suspicious syscalls (e.g., container spawning a shell).
- **Sysdig, Aqua, Tetragon** — broader runtime detection; commercial / open-source variants.
- **Cloud-native equivalents** — AWS GuardDuty, Google Security Command Center.

Look for: unexpected processes spawning, network connections to unusual destinations, modifications to /etc, container escapes.

For mid-size and larger teams, runtime threat detection is increasingly table stakes.

## Secret management

- **Don't bake secrets into images.** (Said before; saying again.)
- **Don't put secrets in environment variables in plain ConfigMaps.** They appear in `kubectl describe`.
- **Use Kubernetes Secrets** (encrypted at rest if you've configured KMS encryption) for basic cases.
- **Use external secret managers** (Vault, AWS Secrets Manager, GCP Secret Manager) for production. Tools like External Secrets Operator sync them.
- **Rotate regularly.** Stale secrets are a smell.

For high-stakes secrets, mount via short-lived token from the manager, not as long-lived env vars.

## Container image lifecycle

A defensible pipeline:

```
Code commit
  ↓
CI builds image (SLSA-attested)
  ↓
Image scanned for CVEs and secrets
  ↓
Image signed with Cosign
  ↓
Image pushed to private registry
  ↓
Admission controller verifies signature on deploy
  ↓
Pod runs with non-root, restricted PSA, network policies, runtime detection
```

Each step adds a layer. None are individually expensive; the cumulative defence is strong.

## Patching cadence

Container images become stale. Even a patched application has unpatched base images.

Rebuild and redeploy on a regular cadence — weekly is typical for production. Automated tools (Renovate, Dependabot) for dependency updates; rebuild your base images when upstream releases security updates.

A container image deployed 2 years ago and never updated is a pile of unpatched CVEs.

## Common failure modes

- **Privileged containers in production.** "It worked in dev so we shipped it." Privileged means root on the host. Audit; remove.
- **HostPath mounts of sensitive directories.** `/var/run/docker.sock` mounted into a container = Docker daemon takeover.
- **Default-allow network policies.** Or no network policies at all. Lateral movement ready.
- **Stale base images.** Never updated; CVE backlog grows.
- **Public registry by default.** "Pull from Docker Hub anonymously." Replace with private registry or proxy with caching.
- **No image-signature enforcement.** "We sign images but don't verify on deploy." Sign and verify; otherwise the signing was theatre.

## A pragmatic baseline

For a Kubernetes deployment running in production:

1. **Distroless or slim base images** for application containers.
2. **Image scanning** in CI; block deploys on critical CVEs.
3. **No embedded secrets**; use Kubernetes Secrets + KMS encryption.
4. **Non-root, read-only root FS, drop capabilities** in pod security context.
5. **Restricted PSA** on application namespaces.
6. **Network policies** with default-deny and explicit allows.
7. **Image signing + admission verification.**
8. **Falco or equivalent** for runtime detection.
9. **Weekly base-image rebuilds.**
10. **Service mesh with mTLS** for production at scale.

A few weeks of work; defends against the bulk of the threat surface.

## Further reading

- [ContainerOrchestration] — Kubernetes mechanics
- [ApplicationSecurityFundamentals] — broader app-sec context
- [ThreatModeling] — anticipating threats
- [ZeroTrustArchitecture] — broader zero-trust posture
