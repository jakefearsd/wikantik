---
cluster: emergency-prep
canonical_id: 01KQ0P44M9DMT3AH5EVMQ6RS0P
title: Backup Power
type: article
tags:
- emergency-prep
- electrical
- batteries
- generators
- solar
status: active
date: 2025-05-15
summary: Technical guide to sizing and implementing backup power systems. Covers load profiling, battery chemistry (LFP), and generator integration.
auto-generated: false
---

# Backup Power: Systems Engineering

A backup power system must be sized for **Peak Surge (Startup)** and **Daily Energy Consumption (Wh)**.

## 1. Load Profiling: Essential vs. Luxury

Calculate your **Daily Watt-Hour (Wh) Load** to size your battery bank:

| Device | Watts | Daily Use | Total Wh |
| :--- | :--- | :--- | :--- |
| **Refrigerator** | 100W (running) | 8 hrs (cycles) | 800 Wh |
| **Well Pump** | 1500W | 0.5 hrs | 750 Wh |
| **Internet / Comms** | 40W | 24 hrs | 960 Wh |
| **LED Lighting** | 50W | 4 hrs | 200 Wh |

**Total Essential Load:** ~2.7 kWh per day.

### The Surge Constraint
Inductive loads (motors) require 3x-7x their running wattage to start.
*   **Concrete Example:** A 1/2 HP well pump that draws 800W while running may require **3500W+ surge** for 100ms. If your inverter is only rated for 2000W continuous, the system will trip immediately.

## 2. Battery Chemistry: LFP vs. Lead Acid

*   **LiFePO4 (Lithium Iron Phosphate):** 100% Depth of Discharge (DoD) capable, 6,000+ cycles, 10-year life. Constant voltage throughout the discharge curve.
*   **Lead-Acid (AGM/Gel):** Only 50% DoD usable. If you draw a 100Ah Lead-Acid battery to 0%, you will damage the plates. 300-500 cycles.
*   **Requirement:** For any mission-critical backup, use **LiFePO4**. A 100Ah 12V LFP battery provides 1.28 kWh of usable energy. To support the 2.7 kWh daily load above, a minimum of **300Ah** is required for 1 day of autonomy.

## 3. Generators: Dual-Fuel and Inverters

*   **Inverter Generators (e.g. Honda EU2200i):** Produce "Clean" sine wave power ($THD < 3\%$), safe for computers and medical gear.
*   **Dual-Fuel:** Generators that run on Gas and Propane are superior for emergencies. Propane does not degrade over time (shelf-stable for 20+ years), whereas gasoline goes bad in 6-12 months.
*   **Concrete Tip:** To maintain a generator, run it for 20 minutes under 50% load every month. Use a fuel stabilizer (e.g., STA-BIL) for any stored gasoline.

## 4. Integration: Transfer Switches

Never "backfeed" a house by plugging a generator into a wall outlet (suicide cord). This can kill utility workers.
*   **Transfer Switch:** A manual or automatic switch that physically disconnects the house from the grid before connecting the backup source.
*   **Interlock Kit:** A low-cost metal plate on the main breaker panel that prevents the Main Breaker and the Generator Breaker from being ON at the same time.

---
**See Also:**
- [Home Emergency Preparedness](HomeEmergencyPreparedness) — Sizing for whole-home resilience.
- [Extreme Weather Prep](ExtremeWeatherPrep) — Managing grid-failure triggers.
- [Van Remote Work Setup](VanRemoteWorkSetup) — Mobile power budgeting.
