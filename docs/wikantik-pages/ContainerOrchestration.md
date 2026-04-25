---
canonical_id: 01KQ12YDT685CHWKB0H8M8HE55
title: Container Orchestration
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- kubernetes
- containers
- orchestration
- deployment
- platform
summary: Kubernetes by 2026 has won; what to actually use it for, what to skip,
  and the operational decisions (managed vs self-hosted, multi-tenancy, GitOps)
  that decide whether the platform is a leverage or a tax.
related:
- ContainerSecurity
- MicroservicesArchitecture
- ChaosEngineering
- ServiceLevelAgreements
hubs:
- SoftwareArchitecture Hub
---
# Container Orchestration

In 2026 "container orchestration" essentially means Kubernetes. Nomad and ECS still ship; Kubernetes won the market the way Linux won server OSes — not because it's the best in every dimension but because it's the universal default and the ecosystem makes everything else expensive.

This page is what to know if you have to operate Kubernetes, and the (rarer) cases where the answer is "use something else."

## What Kubernetes actually gives you

The core value:

- **Declarative state** — you say what you want; the controller loops make it so. Pod died? It restarts. Node died? Pods reschedule.
- **Service abstraction** — pods come and go; the Service IP is stable. Load balancing across replicas is built in.
- **Resource scheduling** — bin-packing pods onto nodes by CPU/memory requests, with affinity/anti-affinity rules.
- **Rolling updates** — replace replicas one at a time, halt on failure.
- **Configuration via manifests** — every cluster object is YAML; review it, version it, audit it.

What it doesn't give you:

- **A development environment.** Local clusters (Minikube, Kind, k3d) are slow and rarely match production.
- **A production-ready default config.** Out-of-box K8s is wide open. Hardening is your job.
- **Cost control.** The cluster will happily burn money. Tracking and optimisation is your job.
- **Application-level concerns** — secrets management, observability, ingress, mesh — separate add-ons.

## Should you even use it

Honest decision tree:

- **< 10 services / very small team** — probably no. ECS Fargate, Cloud Run, App Runner, Render, Fly.io all do less Kubernetes-shaped work for less operational cost.
- **Heavy AWS-only stack** — ECS or App Runner often wins on operational simplicity if you don't need K8s portability.
- **You need multi-cloud / on-prem / hybrid** — Kubernetes is the obvious choice; the alternative is reinventing it.
- **You're past 20-30 services and growing** — Kubernetes' ecosystem advantage starts to dominate.
- **Specific K8s ecosystem features** (Helm, Istio, CRDs you depend on) — you're already in.

The "default to Kubernetes" instinct costs many small teams operating budget they can't afford. Default to managed PaaS for small workloads; promote to K8s when there's a reason.

## Managed vs self-hosted

If you're using Kubernetes, use a managed control plane unless you have a specific reason not to.

- **EKS / GKE / AKS** — major cloud managed offerings. Control plane is theirs; nodes are yours; integrations with their cloud's IAM, networking, storage are deepest.
- **GKE Autopilot** — Google manages everything including node provisioning; you only deal with workloads. Closest to a "PaaS for K8s" experience.
- **DigitalOcean / Linode / OVH managed K8s** — cheaper, less polished, fewer integrations.
- **Self-hosted (kubeadm, kops, Rancher RKE)** — for very specific compliance or network constraints. Operating overhead is real (3-5 dedicated SREs at scale).

Self-hosting K8s is rarely the right call for new teams in 2026. The major use cases are:

- On-prem / air-gapped environments.
- Specific compliance regimes that disallow shared control planes.
- Cost optimisation at extreme scale (thousands of nodes).

Otherwise, pay for the managed option. The labour cost difference dwarfs the service fee.

## The minimum production setup

For a managed cluster running real workloads:

- **Multiple node pools** — separate pools for different workload types (CPU, memory, GPU). Different scaling policies.
- **Cluster autoscaler** — nodes scale on demand. Configured carefully (taints, surge limits) to avoid runaway scaling.
- **Pod Disruption Budgets** — prevents drain operations from killing all your replicas at once.
- **Network policies** — default-deny pod-to-pod; explicit allow lists. Many teams skip this and live with flat networks; not advised.
- **Resource requests and limits on every pod** — without them, scheduling is poor and noisy neighbours are common.
- **Liveness, readiness, and startup probes** — correctly configured. The difference between "K8s works for you" and "K8s works against you" is mostly here.
- **External-DNS + cert-manager** — DNS records and TLS certs auto-provisioned.
- **Ingress controller** — NGINX, Traefik, Envoy-based, or cloud LB-based.
- **GitOps** (ArgoCD or Flux) — manifests in git; cluster syncs. Auditable, reversible, the only sane way to manage at scale.

Each of these is a real engineering investment. The cumulative time is weeks for a single team setting up its first production cluster.

## Helm, Kustomize, and the manifest question

Three approaches to managing the YAML:

- **Helm** — templating + package manager. Charts for off-the-shelf software (Postgres operator, Redis, ingress). Templating syntax is widely complained about; nothing better has stuck.
- **Kustomize** — overlays + patches. Built into kubectl. Cleaner for hand-written manifests with environment variants.
- **Plain manifests + a templating tool of your choice** — sometimes simplest.

For consuming third-party operators, you'll use Helm; the ecosystem is built on it. For your own apps, Kustomize is often cleaner. Don't fight it; both have a place.

## Service mesh: when it earns its keep

Istio, Linkerd, and (newer) Cilium service mesh add:

- mTLS between pods (without app changes).
- Retries, timeouts, circuit breakers (without app changes).
- Detailed metrics (RED metrics per service).
- Traffic shifting (canary, blue-green at the service level).

Cost: the mesh itself is real ops work. Sidecar proxies double pod count. Network is more complex.

Adopt when:

- You have enough services that doing this in libraries hurts.
- You need policy enforcement (mTLS everywhere, network policies enforced).
- You want zero-app-change observability of the whole network.

Skip when:

- You have 5 services and a load balancer.
- Your team can't operate the additional complexity.

Linkerd is the simpler/easier; Istio is more powerful and more complex. Cilium service mesh is the newer entrant that does service mesh without sidecars (eBPF-based) and is increasingly chosen for new clusters.

## Operators and CRDs

Kubernetes is extensible. Operators are programs that manage application-specific resources (e.g. "create a Postgres cluster" → Postgres operator handles failover, backups, scaling).

Strong opinion: **prefer operators for stateful workloads**. Postgres operator, Redis operator, Kafka operator. Manually managing stateful systems on K8s is a pain; operators encode the operational knowledge.

Caveat: operators are software you're now operating. Pick mature ones (CrunchyData / CloudNativePG for Postgres, Redis Operator from Redis Inc., Strimzi for Kafka). Avoid hobbyist operators unless you really know what they do.

## Cost optimisation

Kubernetes cost spirals quietly. Common drains:

- **Over-provisioned requests.** Pods reserve more than they use; nodes underutilised. Run VPA (Vertical Pod Autoscaler) in recommend mode; right-size.
- **No spot / preemptible instances.** Compute on spot can be 60-80% cheaper. Many workloads tolerate it; many teams never enable it.
- **Idle clusters.** Dev environments running 24/7. Schedule down outside hours.
- **Egress fees.** Cross-AZ traffic can dominate the bill in chatty service architectures.

Tools: Kubecost (cost attribution per namespace/team), Karpenter (advanced autoscaler with better bin-packing than the default), Goldilocks (recommends right-sized requests).

A team running K8s that hasn't actively optimised costs is usually 20-50% over their efficient cost.

## Failure modes

- **Resource starvation cascades.** One pod has a memory leak; eats node memory; OOM-killer takes out other pods. Defence: tight resource limits, eviction policies tuned, monitoring node memory.
- **Stuck rollouts.** New deployment never becomes ready; old pods kept around forever. Defence: progress deadlines on deployments, readiness probe sanity, alerting on stuck rollouts.
- **DNS instability.** Internal service-discovery DNS slow or flaky; everything degrades. Defence: NodeLocalDNS or equivalent caching.
- **Networking weirdness.** Pod-to-pod connectivity failing in obscure ways; usually CNI plugin or kernel-version interaction. Have a known-good debugging sequence.
- **etcd / control plane saturation.** Control plane gets slow under heavy CRD churn; cluster becomes unmanageable. Managed providers protect against this; self-hosted operators sometimes don't.
- **Wedged volumes.** PV stuck in some bad state; reattach failures. K8s storage is more fragile than ephemeral compute; design around it.

## When to look beyond Kubernetes

- **Function-shaped workloads.** Lambda, Cloud Run, App Runner. K8s is heavy for "run this code on a request."
- **Workflows / DAGs.** Airflow, Argo Workflows, Temporal. K8s underneath, but the abstraction you operate is the workflow tool.
- **Edge / IoT.** K3s (lighter K8s), or non-K8s runtimes. Full Kubernetes is too heavy for edge.
- **Stateful HPC / heavy GPU.** Slurm and HPC schedulers still beat K8s for some workloads.

## Further reading

- [ContainerSecurity] — security on top of orchestration
- [MicroservicesArchitecture] — the architecture style most K8s users have
- [ChaosEngineering] — testing K8s reliability claims
- [ServiceLevelAgreements] — defining what "production-ready" means for K8s services
