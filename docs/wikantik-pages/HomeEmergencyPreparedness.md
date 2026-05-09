---
title: Home Emergency Preparedness
cluster: emergency-prep
tags: [emergency-prep, resilience-engineering, home-automation, systems-engineering, risk-management]
status: active
date: 2025-05-15
summary: Technical specifications for residential resilience. Covers LiFePO4 battery sizing, water circularity, and immediate shelter-in-place requirements.
auto-generated: false
---

# Home Emergency Preparedness: Resilience Engineering

Residential resilience is defined by the home’s ability to function as an autonomous node during grid instability.

## 1. Power Resilience: The Islanding Standard

The goal is **Islanding Capability**—disconnecting from the macro-grid to operate indefinitely on local generation.

*   **Essential Load Backup (4–10 kWh):** Sustains refrigeration (approx. 1-2 kWh/day) and critical medical devices (CPAP: ~0.5 kWh/day). 
*   **Concrete Hardware:** Use a server-rack battery system (e.g., EG4 LiFePower4 48V 100Ah = 5.1kWh). LiFePO4 chemistry provides 6,000+ cycles and eliminates the thermal runaway risk of NMC lithium-ion.
*   **Inverter Spec:** Requires a Hybrid Inverter (e.g., Sol-Ark 15K or Victron MultiPlus) with an Automatic Transfer Switch (ATS) capable of sub-20ms switchover to prevent computers from rebooting.

## 2. Water Resource Management

Water security requires minimum dynamic storage and active filtration.

*   **Storage Benchmark:** 1 gallon/person/day. For a family of 4, a 14-day buffer requires **56 gallons**.
*   **Concrete Storage:** Use two 55-gallon High-Density Polyethylene (HDPE) blue drums. Treat with **Calcium Hypochlorite** (8 drops of 5-6% unscented bleach per gallon) or specialized water preserver drops (Sodium Chlorite) to prevent algae/bacteria growth for up to 5 years.
*   **Filtration:** Keep a Berkey or Sawyer Squeeze filter to treat runoff or collected rainwater down to 0.1 microns if drums are depleted.

## 3. HVAC and Thermal Sustainment

If the primary heat source (natural gas furnace) fails due to grid loss (the blower motor requires 120V AC):

*   **Backup Heat:** A Mr. Heater "Buddy" series runs on 1lb or 20lb propane cylinders. It generates up to 18,000 BTU/hr.
*   **Safety Requirement:** Indoor propane combustion consumes oxygen and produces moisture. You must crack a window at least 1 inch for makeup air, and a battery-powered **Carbon Monoxide (CO) detector** is mandatory.

## 4. Sanitation (Shelter-In-Place)

If municipal water pressure drops, toilets will not flush after the tank is emptied.

*   **Dry Sanitation:** Utilize the "Two Bucket" system (one for urine, one for feces). 
*   **Carbon Cover:** Cover solid waste with a carbon source (sawdust, peat moss, or dry leaves) to maintain a 30:1 Carbon-to-Nitrogen ratio, which eliminates odor and begins aerobic composting. Do not use cat litter, as clay does not compost.

---
**See Also**:
* [Emergency Prep Hub](EmergencyPrepHub) — The central cluster index.
* [Home Hardening](HomeHardening) — Physical structural resilience.
* [Backup Power](BackupPower) — Deep-dive into inverter technology.
