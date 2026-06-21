---
status: active
date: 2025-05-15T00:00:00Z
summary: VLANs vs. microsegmentation for network isolation — how identity-based policy
  and workload-level enforcement contain lateral movement blast radius.
tags:
- security
- zero-trust
- microsegmentation
- vlan
- blast-radius
type: article
auto-generated: false
cluster: security
canonical_id: 01KQ0P44T0R07N7DE30089MGNW
title: Network Segmentation
---

# Network Segmentation: Containing the Blast Radius

Network segmentation is the architectural practice of dividing a network into smaller, isolated sub-networks to improve security and performance. Its primary goal is the containment of lateral movement by an attacker.

## 1. Traditional Segmentation: VLANs and ACLs

Traditional segmentation operates at Layer 2 (Data Link) and Layer 3 (Network).

### 1.1 VLANs (Virtual LANs)
VLANs group devices into distinct broadcast domains. Communication between VLANs requires a Layer 3 device (Router or Firewall).
*   **Pros:** Easy to implement on existing hardware; reduces broadcast noise.
*   **Cons:** Coarse granularity. Once inside a VLAN, an attacker can usually access all other hosts in that segment (East-West traffic).

### 1.2 Access Control Lists (ACLs)
Stateless rules applied to router interfaces to permit or deny traffic based on IP/Port.
*   *Weakness:* Difficult to manage at scale; lacks stateful inspection (cannot track the context of a connection).

## 2. Microsegmentation: Workload-Centric Isolation

Microsegmentation is a key pillar of **Zero Trust Architecture**. It moves the enforcement point from the network edge to the individual workload (VM, Container, or Process).

### 2.1 Identity-Based Policy
Unlike VLANs, microsegmentation uses **Tags and Metadata** rather than IP addresses.
*   *Policy Example:* "Allow `Billing-App` to communicate with `Billing-DB` on port 5432."
*   This policy follows the workload even if its IP address changes during a migration or auto-scaling event.

### 2.2 Containment of the "Blast Radius"
If a web server is compromised in a microsegmented environment, the attacker is trapped. They cannot scan the rest of the subnet because the local firewall on the compromised host (or the hypervisor) denies all traffic except that which is explicitly permitted by the central policy.

## 3. Technical Comparison

| Feature | Traditional (VLAN/Subnet) | Microsegmentation |
| :--- | :--- | :--- |
| **Enforcement Point** | Edge Firewall / Router | Host Agent / Hypervisor / CNI |
| **Granularity** | Network Level (10.0.1.0/24) | Application/Process Level |
| **Visibility** | North-South (Inbound/Outbound) | East-West (Lateral) |
| **Trust Model** | Implicit trust within segment | Zero Trust (Never trust, always verify)|
| **Complexity** | Low | High (Requires orchestration) |

## 4. Implementation with Service Mesh

In cloud-native environments (Kubernetes), microsegmentation is often implemented via a **Service Mesh** (e.g., Istio, Linkerd) using Mutual TLS (mTLS).
1.  **Authentication:** Every service has an identity (SPIFFE).
2.  **Authorization:** L7 policies dictate which service can call which API endpoint.
3.  **Encryption:** All traffic between segments is encrypted by default.

## 5. Summary

Modern security requires moving beyond the "Castle and Moat" model. Traditional segmentation provides necessary coarse-grained boundaries, but microsegmentation is required to achieve a resilient Zero Trust posture and effectively minimize the blast radius of a potential breach.
