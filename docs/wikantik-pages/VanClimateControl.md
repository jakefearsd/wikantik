---
cluster: van-life
canonical_id: 01KQ0P44Y8K1YM9DXZ69AD3XP4
title: "Van Climate Control: Thermal Engineering"
type: article
tags:
- van-life
- hvac
- thermodynamics
- diesel-heaters
- insulation
- ventilation
status: active
date: 2025-05-15
summary: Technical specifications and calculations for heating, cooling, and moisture management in mobile habitats. Includes BTU load modeling and insulation R-value analysis.
auto-generated: false
---

# Van Climate Control: Thermal Engineering

Managing the thermal envelope of a steel-chassis vehicle requires addressing extreme conductive gains and losses. Steel has a thermal conductivity ($k$) of approx. 45 W/m·K, making the chassis a massive thermal bridge that bypasses insulation if not correctly decoupled.

## 1. Heating: BTU Load Calculation

A standard 144" WB Sprinter has approximately 350 sq ft of interior surface area. To maintain a 40°F temperature differential ($\Delta T$) with an average R-value of 5:

$$Q = \frac{A \times \Delta T}{R} = \frac{350 \times 40}{5} = 2800 \text{ BTU/hr}$$

### Heating Hardware Comparison
| Unit Type | Output (kW) | Output (BTU) | Fuel Consumption | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **2kW Diesel** | 2.0 | ~6,824 | 0.12 - 0.24 L/hr | Sufficient for most vans down to 0°F. |
| **5kW Diesel** | 5.0 | ~17,060 | 0.15 - 0.50 L/hr | Overkill; causes "sooting" if run on low. |
| **Propane (Propex HS2000)** | 1.9 | ~6,500 | 142g/hr | Dry heat, but requires propane infrastructure. |

**Concrete Implementation:** For high-altitude operation (>5000ft), Espar/Webasto units require a high-altitude kit (pressure sensor) to adjust the fuel-to-air ratio, preventing carbon buildup. "Chinese Diesel Heaters" often require manual Hz adjustment of the fuel pump.

## 2. Cooling: Sensible vs. Latent Loads

Cooling a van is significantly harder than heating due to solar radiation. A white roof can be 40°F cooler than a black roof in direct sun.

*   **12V Air Conditioners:** Dometic RTX 2000 (approx. 6,800 BTU) draws ~19A in Eco mode and ~58A in Boost. 
*   **Solar Gain Mitigation:** A 3M Thinsulate (SM600L) layer provides an R-value of approx. 5.2. Combining this with a **Thermal Break** (1/4" closed-cell foam strips over the metal ribs) is mandatory to prevent conductive "ghosting" where the heater/AC fights the chassis directly.

## 3. Moisture and Psychrometrics

An average human exhales ~40g of water vapor per hour while sleeping. In a 300 cu ft van, this quickly reaches the dew point on cold steel surfaces.

*   **Critical Detail:** Do not use fiberglass insulation; it traps moisture against the skin, causing rust. Use hydrophobic materials like 3M Thinsulate or treated sheep wool (Havelock).
*   **Active Venting:** A MaxxAir Fan at 10% speed moves ~100 CFM. For a 300 cu ft van, this provides 20 air changes per hour (ACH), sufficient to keep $T_{dp}$ (Dew Point) below the surface temperature of the walls.

---
**See Also:**
- [Van Water Systems](VanWaterSystems) — Managing freeze protection.
- [Backup Power](BackupPower) — Sizing battery banks for AC loads.
- [Home Hardening](HomeHardening) — Comparative insulation strategies.
