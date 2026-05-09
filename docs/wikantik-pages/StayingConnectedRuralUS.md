---
cluster: van-life
canonical_id: 01KQ0P44WYDPSG1PWPCKV74V5C
title: Staying Connected in Rural US
type: article
tags:
- van-life
- connectivity
- telecommunications
- cellular
- starlink
- networking
status: active
date: 2025-05-15
summary: Technical guide to mobile internet in the US. Covers cellular bands (B12/B71), antenna configurations, Starlink optimization, and bonded networking.
auto-generated: false
---

# Staying Connected in Rural US: Technical Implementation

For the "digital nomad," connectivity is a tiered utility requiring multiple redundant radio access technologies.

## 1. Cellular Infrastructure: Bands and Propagation

Not all cellular signals are equal. In rural areas, "Low-Band" frequencies are essential for range:

*   **Verizon/AT&T Band 12/13 (700 MHz):** The "Gold Standard" for rural penetration.
*   **T-Mobile Band 71 (600 MHz):** Provides superior range in Western US canyons and forests.
*   **Hardware Requirement:** Ensure your modem (LTE Cat 12 or 5G) supports these bands. Use a **Peplink MAX BR1 Pro 5G** or a **GL.iNet GL-X3000** for professional-grade reliability.

## 2. Antenna Systems: dBi and MIMO

Internal router antennas are shielded by the van's metal chassis (a Faraday cage). 

*   **MIMO (Multiple Input Multiple Output):** 4x4 MIMO is the current standard for 5G. This requires 4 separate antenna leads.
*   **Gain (dBi):** A 5-7 dBi external antenna (e.g. Poynting 7-in-1 or Parsec Akita) is often better than a high-gain 12 dBi directional antenna because it doesn't require precise aiming.
*   **Concrete Tip:** Use **LMR-240 or LMR-400** low-loss coaxial cable for runs over 10 feet. Cheap RG-58 cable can lose 50% of your signal strength before it reaches the router.

## 3. Starlink Optimization

*   **Power:** The Starlink V2 (Actuated) draws ~50W-75W. For van use, convert the dish to **12V/48V DC power** using a PoE injector (e.g., DishyPowa or Yaosheng) to bypass the inefficient 120V AC inverter, saving ~20% battery capacity.
*   **Obstructions:** Download the Starlink app and use the "Check for Obstructions" tool. Even a single tree branch can drop a Zoom call every 2 minutes.

## 4. Bonded Networking (SpeedFusion)

To achieve "99.9% Uptime," use **Network Bonding** rather than simple Failover. 
*   **How it works:** Software (Peplink SpeedFusion or Speedify) splits your data packets across Starlink and Cellular simultaneously. If one link drops, the session remains active without interruption.
*   **Concrete Example:** Set Starlink as the "Primary" and a low-bandwidth LTE plan as "Warm Standby." This minimizes data costs while ensuring zero-latency failover during "no-satellite" gaps.

---
**See Also:**
- [Local Network For Nomads](LocalNetworkForNomads) — WiFi security and local storage.
- [Van Remote Work Setup](VanRemoteWorkSetup) — Ergonomics and power budgeting.
- [Emergency Communication](EmergencyCommunication) — Radio-based backups (GMRS/Ham).
