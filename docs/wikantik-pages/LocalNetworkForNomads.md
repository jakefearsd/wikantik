---
status: active
date: 2025-05-15T00:00:00Z
summary: Technical blueprint for a resilient, local-first nomadic network. Covers
  Tailscale VPN, local NAS, and Zigbee automation without cloud dependency.
tags:
- van-life
- local-network
- cybersecurity
- off-grid-tech
type: article
auto-generated: false
cluster: van-life
canonical_id: 01KQ0P44S1YY2TQ2JHS43C7GD1
title: Local Network for Nomads
---

# Local Network For Nomads: Architecture and Security

Relying on cloud services while nomadic introduces unacceptable latency and downtime. A robust mobile node must operate on a **Local-First Architecture**.

## 1. The Compute Core: Low-Power Servers

A standard 1U rack server draws too much power (~150W idle) for a van.
*   **Hardware:** Use a Raspberry Pi 4 or 5, or an Intel N100 Mini-PC (e.g., Beelink S12). The Intel N100 idles at **6W-10W** while providing sufficient hardware transcoding (QuickSync) for media servers.
*   **Storage:** Use Solid State Drives (SSDs) exclusively. Mechanical HDDs will suffer head-crashes from road vibrations. A 4TB Crucial MX500 draws <3W under load.

## 2. Local-First Automation (Home Assistant)

Cloud-dependent smart home devices (e.g., Tuya Wi-Fi switches) become bricks without internet. 
*   **Protocol:** Use **Zigbee** or **Z-Wave**. These are local mesh networks that operate independently of the Wi-Fi router.
*   **Hardware:** Connect a ConBee II or Sonoff Zigbee 3.0 USB Dongle to the Mini-PC running Home Assistant OS.
*   **Concrete Use Case:** Program an automation that monitors the Victron Cerbo GX via Modbus TCP: *If battery State of Charge (SoC) drops below 40% AND solar input is <50W, automatically shut off the electric water heater relay.*

## 3. Remote Access and Virtual LANs

When you leave the van, you still need to monitor its telemetry (temperature, security cameras, battery status). Opening ports on a cellular router is a massive security risk due to Carrier-Grade NAT (CGNAT).

*   **Solution:** Use an Overlay Network like **Tailscale** or **ZeroTier**. These utilize WireGuard to punch through CGNAT, creating a secure P2P tunnel between your phone and the van's server.
*   **Concrete Configuration:** Install Tailscale on the van's GL.iNet router and advertise the van's local subnet (e.g., `192.168.8.0/24`). You can then access the Victron dashboard or Home Assistant locally from anywhere in the world.

## 4. Local File Synchronization

Do not rely on Google Drive or Dropbox for critical document sync when bandwidth is metered (e.g., Starlink 1TB cap) or cellular is spotty.
*   **Software:** Use **Syncthing** (an open-source CRDT-based tool). It synchronizes folders between your laptop, phone, and the van's Mini-PC directly over the local Wi-Fi, transferring data at gigabit speeds without touching the external internet.

---
**See Also:**
- [Staying Connected Rural US](StayingConnectedRuralUS) — External cellular bonding.
- [Van Remote Work Setup](VanRemoteWorkSetup) — Powering the workstation.
- [Backup Power](BackupPower) — Calculating the 24/7 draw of the network stack.
