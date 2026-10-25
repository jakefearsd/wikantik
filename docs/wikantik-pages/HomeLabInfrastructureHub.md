---
kg_include: true
type: hub
summary: 'Home lab infrastructure hub: Proxmox VE hypervisors, ZFS storage arrays,
  Ceph clustering, k3s Kubernetes, and low-power hardware optimization.'
audience:
- humans
date: '2026-08-08'
canonical_id: 01KZG1VN8VRRZD33PBGG6H6Y6R
status: active
tags:
- home-lab
- proxmox
- virtualization
- zfs
- kubernetes
- k3s
- networking
- self-hosting
cluster: home-lab-infrastructure
title: 'Home Lab Infrastructure Hub: Proxmox Virtualization, Storage, and Kubernetes
  Clusters'
related:
- HostDocker1
- HostDocker2
- HostCloudflare
- NasAndOffsiteBackups
- HomeLabNetworkAndDns
- SoftwareProjectsHub
- HomeLabFleetAndIngress
- HostInference
- JakemonHub
verified_at: '2026-10-24T15:40:21.014087794Z'
verified_by: jakefear
---
# Home Lab Infrastructure Hub: Proxmox Virtualization, Storage, and Kubernetes Clusters

A modern self-hosted home lab provides an enterprise-grade sandboxed environment for testing distributed systems architectures, self-hosting privacy-first services, running localized LLM inference clusters, and experimenting with container orchestration.

This hub organizes hypervisor configuration, high-availability storage pools, localized networking, and automated backup topologies.

## This lab's actual fleet

The sections below are general background. For the machines that actually run here, and the single way public traffic gets in, start with [Home Lab Fleet and Ingress](HomeLabFleetAndIngress). Then read the host pages: [cloudflare](HostCloudflare) (the ingress), [docker1](HostDocker1), [docker2](HostDocker2) and [inference](HostInference), which runs the jakemon monitoring stack ([JakemonHub](JakemonHub)). Where secrets, API keys, and `.env` files live across hosts and repositories is indexed in [CredentialsLocator](CredentialsLocator).

---

## 1. Quick-Reference: Home Lab Infrastructure Layers

```
+-----------------------------------------------------------------------------------------+
|                               HOME LAB INFRASTRUCTURE STACK                             |
+-----------------------------------------------------------------------------------------+
| Layer                  | Technologies                      | Architectural Role         |
+------------------------+-----------------------------------+----------------------------+
| Hypervisor & Compute   | Proxmox VE, KVM, LXC Containers   | Virtualization & clustering|
| Storage & Filesystem   | TrueNAS, ZFS RAIDZ2, Ceph Cluster | Data persistence & bitrot  |
| Container Orchestration| k3s Kubernetes, Docker Compose    | Microservices & agent apps |
| Network & Routing      | OPNsense, VLANs, WireGuard, Pihole| Segmented secure traffic   |
| Backup & Disaster Rec. | ZFS Snapshot Replication, Restic  | 3-2-1 immutable offsite    |
+-----------------------------------------------------------------------------------------+
```

---

## 2. Core Deep Dives

- **[HomeLabNetworkAndDns](HomeLabNetworkAndDns)**: VLAN isolation, split-horizon DNS, and WireGuard VPN tunnels.
- **[NasAndOffsiteBackups](NasAndOffsiteBackups)**: ZFS block-level snapshots, pull-mode backups, and immutable cloud storage.
- **[CPUInference](CPUInference)**: Running quantized local LLMs on home lab CPU/GPU hardware.
