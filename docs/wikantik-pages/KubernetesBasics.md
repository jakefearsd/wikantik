---
summary: Architectural analysis of Kubernetes primitives (Pods, Services, Deployments)
  and their interaction in a distributed control plane.
cluster: cloud-platforms
auto-generated: false
canonical_id: 01KQ0P44RN4JQ8FHGVS5RHS1RS
title: Kubernetes Basics
type: article
tags:
- kubernetes
- orchestration
- pod
- service
- deployment
hubs:
- ContainerSecurity Hub
---

Kubernetes is a declarative control plane that manages the lifecycle of containerized workloads. It abstracts physical infrastructure into a set of logical primitives: Pods, Services, and Deployments.

## The Atomic Unit: The Pod

A **Pod** is the smallest deployable unit. It groups one or more containers that share a network namespace (localhost) and storage volumes.

### The Sidecar Pattern
The Pod's shared network namespace enables the **Sidecar Pattern**, where auxiliary tasks (logging, service mesh proxying, secret rotation) are decoupled from the primary application container.

**Technical Constraint:** All containers in a Pod share a single IP. Port conflicts must be managed at the container level within the Pod.

## Management: Deployments and ReplicaSets

A **Deployment** manages the desired state of a set of Pods. It is a high-level abstraction over **ReplicaSets**.

### Rolling Update Mechanics
When a Deployment is updated (e.g., new image version), the Deployment Controller:
1. Creates a new ReplicaSet ($RS_{new}$).
2. Scales up $RS_{new}$ while scaling down the old ReplicaSet ($RS_{old}$).
3. Monitors **Readiness Probes** to ensure $RS_{new}$ Pods are healthy before terminating $RS_{old}$ Pods.

**Rollback Strategy:** If $RS_{new}$ fails health checks, the controller can be instructed to revert to $RS_{old}$ immediately, leveraging the immutable history of ReplicaSet revisions.

## Networking: The Service Abstraction

Pods are ephemeral; their IPs change on every restart. A **Service** provides a stable virtual IP (ClusterIP) and DNS name for a set of Pods.

### Service Discovery (kube-proxy)
`kube-proxy` runs on every node and manages the mapping from the Service IP to the healthy Pod IPs using:
- **iptables:** The legacy default; uses sequential rule matching.
- **IPVS:** The modern high-performance option; uses hash-based lookups for $O(1)$ routing even with thousands of services.

### Exposure Modes
- **ClusterIP:** Internal-only communication.
- **NodePort:** Exposes the service on a fixed port on every node's IP.
- **LoadBalancer:** Provisions a cloud-native external load balancer (e.g., AWS NLB).

## Reliability: Probes as Contracts

Kubernetes relies on three probe types to manage the "Self-Healing" loop:
1. **Liveness Probe:** "Is the process stuck?" If it fails, the container is restarted.
2. **Readiness Probe:** "Can I send production traffic?" If it fails, the Pod is removed from the Service endpoint list.
3. **Startup Probe:** "Is the slow-starting process done yet?" Disables liveness/readiness until the first success.

## Resource Management (QoS Classes)

Resource `requests` and `limits` define the Pod's **Quality of Service (QoS)**:
- **Guaranteed:** Requests == Limits. Highest priority; last to be evicted.
- **Burstable:** Requests < Limits. Medium priority.
- **BestEffort:** No requests/limits. First to be terminated under node pressure.

## Summary of Primitives

| Component | Responsibility | Failure Mode |
|---|---|---|
| **Pod** | Execution & Co-location | Ephemeral; dies with the Node. |
| **Service** | Stable Networking | Points to old IPs if selector is wrong. |
| **Deployment** | Versioning & Scaling | Rollout stall if probes never pass. |
| **ConfigMap/Secret** | State & Configuration | Stale data if application doesn't watch for updates. |
