---
cluster: emergency-prep
canonical_id: 01KQ0P44KP6BPKC8WV7C2JG6H5
title: Alternative Energy
type: article
tags:
- emergency-prep
- solar
- energy-storage
- off-grid
- electrical
status: active
date: 2025-05-15
summary: Technical guide to residential and mobile solar power systems. Covers MPPT vs PWM, battery chemistry (LFP), and sizing calculations for energy autonomy.
auto-generated: false
---

# Alternative Energy: Solar Systems Engineering

Alternative energy for resilience focuses on **Photovoltaic (PV)** generation and **Lithium Iron Phosphate (LiFePO4)** storage.

## 1. PV Generation: MPPT vs. PWM

The charge controller is the critical link between panels and batteries.
*   **PWM (Pulse Width Modulation):** Acts as a switch. If a 100W panel (18V) is charging a 12V battery, the PWM forces the panel to 12V, losing ~30% of the power.
*   **MPPT (Maximum Power Point Tracking):** Uses a DC-to-DC transformer to match the panel's Vmp (voltage at max power) to the battery voltage, harvesting up to 98% of available power.
*   **Concrete Requirement:** Use MPPT for any system >100W or where panel voltage is significantly higher than battery voltage (e.g., 24V panels charging a 12V bank).

## 2. Energy Storage: LiFePO4 Chemistry

For off-grid and backup systems, **LiFePO4 (LFP)** has superseded Lead-Acid (AGM/Gel).

| Metric | LiFePO4 (LFP) | Lead-Acid (AGM) |
| :--- | :--- | :--- |
| **Cycle Life** | 4,000 - 6,000 | 300 - 500 |
| **Usable Capacity**| 100% | 50% |
| **Energy Density** | ~100 Wh/kg | ~30 Wh/kg |
| **Charge Rate** | 0.5C - 1C | 0.1C - 0.2C |

**Concrete Example:** A 100Ah LFP battery provides 1.28 kWh of usable energy. An equivalent 100Ah AGM battery only provides 0.64 kWh safely. Charging the LFP at 0.5C (50A) takes 2 hours; the AGM takes 10 hours at 0.1C to reach 100%.

## 3. Sizing the Array: The "3-Day Rule"

A resilient system must handle consecutive cloudy days.
*   **Load Calculation:** If daily use is 1.5 kWh, a 3-day buffer requires **4.5 kWh** of storage (approx. four 100Ah LFP batteries).
*   **Solar Replenishment:** To recharge that 1.5 kWh daily load in 5 peak sun hours:
    $$
    \text{Watts Required} = \frac{1500\text{ Wh}}{5\text{ hrs} \times 0.75\text{ (efficiency)}} \approx 400\text{W}
    $$
*   **Concrete Layout:** Two 200W panels in **Series** (to increase voltage for MPPT efficiency) or **Parallel** (to mitigate shading impact on one panel).

## 4. Inverters and Efficiency

*   **Pure Sine Wave:** Mandatory for electronics and motors. Modified Sine Wave inverters generate harmonic distortion that causes motors (refrigerators) to overheat.
*   **Idle Draw:** A 3000W inverter can draw 25W-50W just being ON. In a 24-hour period, this wastes **0.6 - 1.2 kWh**, potentially half of your daily solar yield.
*   **Concrete Tip:** Size the inverter to the load, not the max possible. Use a small 300W inverter for laptops and a large 3000W inverter only for heavy loads (microwave, induction), switching it off when not in use.

---
**See Also:**
- [Backup Power](BackupPower) — Detailed load profiling.
- [Winter Preparedness](WinterPreparedness) — Solar yield degradation in winter.
- [Home Emergency Preparedness](HomeEmergencyPreparedness) — Scaling to whole-home systems.
