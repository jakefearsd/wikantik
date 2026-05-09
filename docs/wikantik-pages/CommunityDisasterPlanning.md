---
cluster: emergency-prep
canonical_id: 01KQ0P44NP8HY6K8D38T89F5N7
title: Community Disaster Planning
type: article
tags:
- emergency-prep
- resilience-engineering
- neighborhood-networks
- communications
- cert
status: active
date: 2025-05-15
summary: Technical guide to neighborhood disaster resilience. Covers LoRa mesh networking, skill mapping, and PACE communications planning.
auto-generated: false
---

# Community Disaster Planning: Neighborhood Resilience

When regional infrastructure fails, the neighborhood must function as a semi-autonomous node. This requires **pre-distributed communications** and **skill-mapping**.

## 1. Communications: The PACE Model

Relying on cellular networks (which fail during power outages or congestion) is an operational failure. Neighborhoods must implement a PACE plan:

| Tier | Technology | Use Case |
| :--- | :--- | :--- |
| **Primary** | WhatsApp / Zello | Day-to-day coordination when internet is active. |
| **Alternate** | **MeshTastic (LoRa)** | Low-power, off-grid text messaging. 1-5 mile range. |
| **Contingency** | GMRS / FRS Radio | Voice coordination (Line-of-Sight). Use Channel 20 (standard prep). |
| **Emergency** | HAM Radio (VHF/UHF) | Reaching external authorities/Repeaters beyond the neighborhood. |

**Concrete Example:** A neighborhood MeshTastic network using "Lilygo T-Beam" nodes allows for encrypted text coordination across a 2-mile radius for weeks on a single battery, bypassing the need for cellular towers.

## 2. Skill and Resource Mapping

Resilience is a function of knowing where the "tools" are before the lights go out. 

*   **Medical:** Identify RNs, EMTs, and Vets. Maintain a central "Trauma Kit" ([VanFirstAidKit](VanFirstAidKit)).
*   **Mechanical:** Identify plumbers, electricians, and those with chainsaws for clearing road debris.
*   **Asset Inventory:** Track who has 500W+ solar, 100+ gallons of stored water, or 4WD vehicles. Use a paper-based or local-only digital ledger (e.g., Syncthing on a local server).

## 3. Resilience Hubs

A Resilience Hub is a pre-designated household or community center equipped with:
1. **Power:** 5kWh+ battery storage and 1000W+ solar to charge neighbors' phones and medical devices.
2. **Water:** A 250-gallon IBC tote or multiple 55-gallon drums with a gravity-fed Sawyer filter.
3. **Intel:** A whiteboard for tracking "Needs" vs "Gives" and a printed map of the local area with critical infrastructure marked.

## 4. Drills and Protocols

*   **Radio Net:** Conduct a weekly "check-in" on GMRS/FRS at a set time (e.g., Sunday 7:00 PM) to ensure equipment is functional and operators are proficient.
*   **The "Go-Signal":** Establish a protocol for what triggers the hub activation (e.g., "Power out for >2 hours" or "Active wildfire within 10 miles").

---
**See Also:**
- [Emergency Communication](EmergencyCommunication) — Radio programming and protocols.
- [Home Emergency Preparedness](HomeEmergencyPreparedness) — Hardening your own node.
- [Staying Connected Rural US](StayingConnectedRuralUS) — Redundant internet for hubs.
