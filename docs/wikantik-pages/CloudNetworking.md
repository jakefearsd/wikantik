---
cluster: cloud-platforms
canonical_id: 01KQ0P44NGTSHSTM2TKGRW2KFP
title: Cloud Networking
type: article
tags:
- cloud
- networking
- vpc
- routing
- security
status: active
date: 2025-05-15
summary: Technical guide to Virtual Private Cloud (VPC) architecture. Covers subnet segmentation, routing tables, Transit Gateways, and PrivateLink.
auto-generated: false
---

# Cloud Networking: VPC Architecture

Cloud networking provides a logically isolated virtual network (VPC) within a public cloud, allowing for granular control over IP addressing, routing, and security.

## 1. Network Segmentation: Subnets

A VPC is a large CIDR block (e.g., `10.0.0.0/16`). It is carved into subnets for isolation.
*   **Public Subnets:** Have a route to an **Internet Gateway (IGW)**. Resources have Public IPs.
*   **Private Subnets:** No direct internet route. Outbound access is via a **NAT Gateway**. Resources only have Private IPs.
*   **Isolation Pattern:** Place databases in private-only subnets with no route to the internet, only accessible from the application tier subnets via **Security Group** rules.

## 2. Routing and Connectivity

*   **Route Tables:** Defined at the subnet level. They dictate the "Next Hop" for IP ranges. 
    *   *Default Route:* `0.0.0.0/0` $\to$ IGW (Public) or NAT GW (Private).
*   **VPC Peering:** Connects two VPCs directly. Non-transitive (A-B and B-C does not mean A-C).
*   **Transit Gateway (TGW):** A hub-and-spoke router for connecting hundreds of VPCs and on-premises data centers (via VPN or Direct Connect).

## 3. Private Access to Services

*   **VPC Endpoints (PrivateLink):** Allows resources in a private subnet to talk to managed services (e.g., S3, Kinesis) without traversing the public internet.
*   **Concrete Benefit:** Traffic stays on the cloud provider's private backbone, reducing latency, increasing security, and eliminating NAT Gateway data processing costs.

## 4. Security Controls

*   **Security Groups (SG):** Stateful firewalls at the Instance/ENI level. Inbound and Outbound rules.
*   **Network ACLs (NACL):** Stateless firewalls at the Subnet boundary. Rules are evaluated in order. 
*   **Concrete Tip:** Because NACLs are stateless, an allow rule for port 80 Inbound *must* have a corresponding rule for ephemeral ports (1024-65535) Outbound for the return traffic to function.

---
**See Also:**
- [Api Gateway Patterns](ApiGatewayPatterns) — Entry point security.
- [Cloud Security Fundamentals](Cybersecurity) — General cloud posture.
- [Staying Connected Rural US](StayingConnectedRuralUS) — External connectivity options.
