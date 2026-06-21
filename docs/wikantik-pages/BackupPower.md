---
title: Backup Power
canonical_id: 01KQ0P44M9DMT3AH5EVMQ6RS0P
cluster: emergency-prep
auto-generated: false
type: article
tags:
- emergency-prep
- electrical
- batteries
- generators
- solar
summary: Technical guide to sizing and implementing backup power systems. Covers load
  profiling, battery chemistry (LFP), and generator integration.
date: '2025-05-15T00:00:00Z'
status: active
---

# Backup Power: Systems Engineering

A backup power system must be sized for **Peak Surge (Startup)** and **Daily Energy Consumption (Wh)**. When considering emergency backup power, it is crucial to distinguish between portable "solar generators" and permanent "home microgrid" architectures, as they serve different needs in terms of capacity, reliability, and cost.

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

## 2. Solar Generators (Portable Battery Systems)
The term "solar generator" typically refers to a **portable, all-in-one battery power station** that can be charged via solar panels, the grid, or other sources.
*   **Best For:** Renters, apartments, or homeowners needing backup for small, specific appliances (e.g., lights, Wi-Fi, refrigerator, medical devices) rather than the whole house.
*   **Pros:** Lower upfront cost; plug-and-play simplicity; portable (can be used for camping or emergency backup); no complex installation required.
*   **Cons:** Limited capacity and power output; generally cannot power heavy-draw appliances (like central HVAC) for extended periods; requires manual setup during an outage.

## 3. Home Microgrid (Integrated Solar + Storage)
A home microgrid is a sophisticated, permanent architecture that coordinates multiple energy sources (solar panels, batteries, the utility grid, and sometimes backup generators) into one intelligent system.
*   **Best For:** Homeowners seeking total energy independence, whole-home backup, and resilience against multi-day power outages.
*   **Key Architecture Elements:**
    *   **Hybrid Inverter:** The "brain" of the system that manages power flow between the grid, solar array, and battery. Crucially, it must be **grid-forming**, meaning it can create its own AC frequency to power the home when the main grid fails.
    *   **Battery Energy Storage System (BESS):** Permanent, high-capacity battery units (often Lithium Iron Phosphate/LiFePO4) that store solar energy for use at night or during blackouts.
    *   **Intelligent Load Management:** Automated controls that prioritize essential circuits during an outage, ensuring critical appliances remain powered while non-essential ones are shed.
    *   **Automatic Transfer Switch (ATS):** Detects a grid outage and instantly (within milliseconds) isolates the home from the utility lines to create a safe, self-sustaining "island" mode.
*   **Pros:** Seamless, automatic transition during outages; capable of whole-home power; provides everyday financial value (e.g., peak-shaving, utility bill reduction); 20–30 year lifespan.
*   **Cons:** High initial investment; requires professional installation and permitting.

### Comparison Summary

| Feature | Solar Generator (Portable) | Home Microgrid (Integrated) |
| :--- | :--- | :--- |
| **Installation** | None (Plug-and-play) | Professional, permanent |
| **Transition** | Manual | Automatic (Seamless) |
| **Capacity** | Low to Medium | High to Very High |
| **Utility** | Occasional/Emergency | Daily value + Emergency |
| **Cost** | Low (\$) | High (\$\$\$) |

## 4. Battery Chemistry: LFP vs. Lead Acid

*   **LiFePO4 (Lithium Iron Phosphate):** 100% Depth of Discharge (DoD) capable, 6,000+ cycles, 10-year life. Constant voltage throughout the discharge curve.
*   **Lead-Acid (AGM/Gel):** Only 50% DoD usable. If you draw a 100Ah Lead-Acid battery to 0%, you will damage the plates. 300-500 cycles.
*   **Requirement:** For any mission-critical backup, use **LiFePO4**. A 100Ah 12V LFP battery provides 1.28 kWh of usable energy. To support the 2.7 kWh daily load above, a minimum of **300Ah** is required for 1 day of autonomy.

## 5. Generators: Dual-Fuel and Inverters

*   **Inverter Generators (e.g. Honda EU2200i):** Produce "Clean" sine wave power ($THD < 3\%$), safe for computers and medical gear.
*   **Dual-Fuel:** Generators that run on Gas and Propane are superior for emergencies. Propane does not degrade over time (shelf-stable for 20+ years), whereas gasoline goes bad in 6-12 months.
*   **Concrete Tip:** To maintain a generator, run it for 20 minutes under 50% load every month. Use a fuel stabilizer (e.g., STA-BIL) for any stored gasoline.

## 6. Integration: Transfer Switches

Never "backfeed" a house by plugging a generator into a wall outlet (suicide cord). This can kill utility workers.
*   **Transfer Switch:** A manual or automatic switch that physically disconnects the house from the grid before connecting the backup source.
*   **Interlock Kit:** A low-cost metal plate on the main breaker panel that prevents the Main Breaker and the Generator Breaker from being ON at the same time.

---
**See Also:**
- [Home Emergency Preparedness](HomeEmergencyPreparedness) — Sizing for whole-home resilience.
- [Extreme Weather Prep](ExtremeWeatherPrep) — Managing grid-failure triggers.
- [Van Remote Work Setup](VanRemoteWorkSetup) — Mobile power budgeting.
