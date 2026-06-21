---
date: '2026-06-20'
status: active
summary: 'Microsoft Azure: regions, Availability Zones, ARM control plane, hub-and-spoke
  VNet networking, ExpressRoute, and enterprise Azure OpenAI services.'
tags:
- azure
- cloud-platforms
- networking
- enterprise-cloud
- arm
type: article
canonical_id: 01KR88FWJJ67X7BWDN0DNMH9KE
cluster: cloud-platforms
related:
- GcpFundamentals
- AwsFundamentals
- CloudPlatformsHub
- CloudCostOptimization
- CloudSecurityFundamentals
title: Azure Fundamentals
---
# Azure Fundamentals

Azure is the enterprise cloud. While AWS was built for developers, Azure was built for the existing Microsoft ecosystem. In 2026, it is the fastest-growing hyperscaler at scale, with ~25% market share driven heavily by AI-native service adoption and deep integration with Microsoft 365.

## Part 1: Core Azure Architecture

Azure's architecture operates on two distinct but interconnected planes: the Physical Infrastructure that houses the compute, and the Logical Infrastructure that governs it.

### 1.1 Physical Infrastructure: The Global Foundation
*   **Datacenters:** The atomic unit of Azure's physical presence. 
*   **Availability Zones (AZs):** Physically separate datacenters *within* a single Azure region. Azure maps AZs logically per subscription to ensure load balancing.
*   **Regions & Region Pairs:** A geographic perimeter containing datacenters. Regions are paired within the same geography to enable seamless disaster recovery.

### 1.2 Logical & Management Infrastructure
*   **Management Groups & Subscriptions:** Used to apply governance, compliance, and RBAC across entire fleets of subscriptions. Subscriptions are the primary boundary for billing and scale.
*   **Resource Groups (RGs):** Logical lifecycle containers for resources. 
*   **Identity (Entra ID):** Entra ID is a global identity provider. It manages Azure resources and all Microsoft 365 users.

### 1.3 The Paradigm of Control Plane vs. Data Plane
*   **The Control Plane (The "Brain"):** Managed exclusively through Azure Resource Manager (ARM). Handles lifecycle of resources.
*   **The Data Plane (The "Muscle"):** Handles the actual workloads and data processing. Relies on service-specific endpoints.

## Part 2: Azure Networking Fundamentals

### 2.1 Core Network Components
*   **Virtual Network (VNet) & Subnets:** The fundamental boundary of a private network in Azure, logically subdivided into subnets to group resources by function.

### 2.2 Traffic Management & Load Balancing
*   **Azure Load Balancer:** Layer 4 (TCP/UDP) load balancing.
*   **Application Gateway:** Layer 7 (HTTP/HTTPS) routing, SSL termination, and WAF.
*   **Azure Front Door:** Global, Anycast-based entry point providing CDN capabilities.

### 2.3 Hybrid Connectivity
*   **VPN Gateway:** Utilizes the public internet to establish encrypted IPsec/IKE tunnels.
*   **Azure ExpressRoute:** Bypasses the public internet entirely, establishing a dedicated, private connection.

## Part 3: Deep Dive: The Hub-and-Spoke Topology

The Hub-and-Spoke architecture is the gold standard for enterprise Azure deployments.

### 3.1 Core Architecture & Routing
*   **Hub VNet:** The central point of ingress/egress hosting shared services (Firewall, Gateways, DNS).
*   **Spoke VNets:** Isolated networks peered to the Hub hosting specific workloads.
*   **Routing Mechanics:** VNet Peering links Spokes to the Hub. User-Defined Routes (UDRs) force traffic through centralized security appliances.

### 3.2 Layered Security ("Defense in Depth")
*   **Azure Firewall (Hub):** Centralized stateful inspection and threat intelligence-based filtering.
*   **NSGs & ASGs (Spokes):** Network Security Groups act as distributed micro-firewalls. Application Security Groups allow grouping VMs by purpose.
*   **Private Link / Private Endpoints:** Allows access to Azure PaaS services via a private IP inside the VNet, keeping all data traversal off the public internet.

## The AI Growth Engine (Azure AI)
Azure OpenAI Service provides enterprise-grade access to models (GPT-4o, etc.) with the privacy guarantees that large corporations require.

## Further Reading
- [CloudPlatforms Hub](CloudPlatformsHub)
- [CloudCostOptimization](CloudCostOptimization)
- [CloudSecurityFundamentals](CloudSecurityFundamentals)
- [GcpFundamentals](GcpFundamentals)
- [AwsFundamentals](AwsFundamentals)

## References
