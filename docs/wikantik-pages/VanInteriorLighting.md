---
cluster: van-life
canonical_id: 01KQ0P44YAMZWWPHBR2WSKR5AE
title: Van Interior Lighting
type: article
tags:
- van-life
- electrical
- led
- ergonomics
- lighting-design
status: active
date: 2025-05-15
summary: Technical specifications for 12V LED lighting systems. Covers CRI, CCT, PWM dimming, and power consumption modeling.
auto-generated: false
---

# Van Interior Lighting: Technical Implementation

Lighting in a mobile habitat must balance high-fidelity task illumination with low-power consumption and circadian health.

## 1. Photometric Specifications

For a productive workspace and healthy sleep cycle, specific metrics are non-negotiable:

*   **CRI (Color Rendering Index):** Use LEDs with **CRI > 90**. Low CRI (typical in cheap 12V strips) causes "color wash," making food preparation and wiring difficult due to poor red-spectrum rendering (R9 value).
*   **CCT (Correlated Color Temperature):**
    *   **3000K (Warm White):** Living area and evening use.
    *   **5000K (Daylight):** Task areas (Kitchen, Workbench).
*   **Concrete Example:** Use "Tunable White" COB (Chip on Board) LED strips. COB LEDs eliminate the "dots" associated with SMD strips, providing a continuous line of light without bulky diffusers.

## 2. Electrical Integration and PWM Dimming

Most 12V LEDs are dimmed via **Pulse Width Modulation (PWM)**. 

*   **The Flicker Problem:** Cheap PWM dimmers operate at <200Hz, which causes eye strain and "ghosting" effects. Use dimmers with a frequency of **>1kHz**.
*   **Voltage Regulation:** A van's "12V" system actually ranges from 12.2V (resting) to 14.4V (charging). Direct-driving 12V LED strips results in premature failure. **Requirement:** Use constant-voltage LED drivers or strips with built-in resistors rated up to 15V.

## 3. Zonal Power Modeling

For a standard van layout, calculate the amp-hour (Ah) impact on the battery bank:

| Zone | Qty | Lumens/Unit | Watts/Unit | Daily Use | Total Ah (12V) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Main Pucks** | 6 | 200 | 2.5W | 4 hrs | 5.0 Ah |
| **Task (Kitchen)** | 1m strip | 800 | 10W | 2 hrs | 1.6 Ah |
| **Garage/Cargo** | 2 pucks | 200 | 2.5W | 0.5 hrs | 0.2 Ah |
| **Reading Lights** | 2 | 100 | 1.5W | 3 hrs | 0.75 Ah |

**Total Daily Load:** ~7.5 Ah. This is negligible for a 100Ah LiFePO4 bank, but significant if using a single lead-acid starter battery.

## 4. Implementation Guidelines

*   **Wiring:** Use 18 AWG marine-grade tinned copper wire for most LED runs. Tinned wire resists the corrosion common in high-humidity van environments.
*   **Switching:** Utilize a **3-way switch circuit** for the main cabin lights (one switch at the slider door, one at the bed) to avoid navigating a dark van.
*   **Night Mode:** Implement a secondary circuit of **Red LEDs** for nocturnal navigation. Red light (>650nm) does not trigger the pupillary reflex or suppress melatonin.

---
**See Also:**
- [Van Remote Work Setup](VanRemoteWorkSetup) — Ergonomic workspace lighting.
- [Backup Power](BackupPower) — Sizing the DC bus.
- [Local Network For Nomads](LocalNetworkForNomads) — Smart lighting control via ESP32/Home Assistant.
