---
cluster: van-life
canonical_id: 01KQ0P44QWK49X5W95W09SRWY4
title: Hidden Destinations
type: article
tags:
- geospatial-research
- geothermal
- urban-exploration
- satellite-imagery
- remote-sensing
date: 2025-05-15
summary: Technical methodology for identifying undocumented geothermal sites and industrial ruins using satellite thermography, LiDAR, and historical geospatial data.
auto-generated: false
---

# Geospatial Research for Hidden Destinations

Finding undocumented "hidden" sites—specifically geothermal hot springs and industrial ruins—requires a shift from consumer travel apps to raw geospatial data analysis and remote sensing.

## 1. Remote Sensing for Geothermal Sites
Identifying undocumented hot springs involves searching for thermal anomalies and specific mineralogical signatures that indicate hydrothermal activity.

### A. Satellite Thermography (TIRS)
Using the Thermal Infrared Sensor (TIRS) data from Landsat 8/9, researchers can identify surface temperature anomalies.
*   **Methodology:** Filter for night-time thermal imagery to eliminate solar heating bias. Look for "hot spots" where the surface temperature is significantly higher ($>5^\circ\text{C}$ delta) than the surrounding ambient terrain.
*   **Target Signature:** In winter months, look for areas of "snow melt anomalies" where geothermal heat prevents snow accumulation in otherwise high-elevation or high-latitude regions.

### B. Mineralogical Indicators
Hydrothermal activity often leaves behind distinct surface deposits.
*   **Siliceous Sinter and Travertine:** These minerals appear with high reflectivity in specific multispectral bands. Using the Normalized Difference Sinter Index (NDSI) can help distinguish these deposits from surrounding limestone or basalt.
*   **Vegetation Stress:** Extremely high soil temperatures or high sulfur content from geothermal vents will create "void zones" or stressed vegetation patterns visible via NDVI (Normalized Difference Vegetation Index) mapping.

## 2. Researching Industrial Ruins (Urban Exploration)
Locating abandoned industrial sites ("rust-mining") requires cross-referencing historical land-use data with modern structural analysis.

### A. Historical Topographical Overlay
*   **Method:** Use the USGS Historical Topographic Map Explorer to compare 1920s–1950s maps with modern satellite layers.
*   **Indicator:** Look for "Ghost Foundations"—concrete pads or rail spurs that appear on old maps but are now obscured by forest canopy or overgrowth.
*   **Shadow Volume Analysis:** In high-resolution satellite imagery (Google Earth Pro), analyze shadow lengths at different times of day to estimate the height and structural integrity of standing ruins that may be hidden within dense foliage.

### B. LiDAR and Digital Elevation Models (DEM)
LiDAR (Light Detection and Ranging) can "see through" tree canopies by mapping the ground return signals.
*   **Technical Use:** Search for unnatural geometric regularity. Nature rarely produces perfect right angles or 200-meter straight lines. A perfectly straight "ridge" in a LiDAR DEM often indicates an abandoned railway embankment or a buried pipeline.

## 3. Operational Safety and Legal Frameworks

### A. Structural Integrity Assessment
When exploring industrial ruins, structural failure is the primary risk.
*   **Concrete Carbonation:** Over decades, $\text{CO}_2$ penetrates concrete, lowering its pH and causing the internal steel rebar to rust and expand, leading to "spalling" and catastrophic floor failure.
*   **Asbestos and Heavy Metals:** Assume all pre-1980 industrial sites contain friable asbestos (insulation) and lead-based paint. Use a P100-rated respirator at minimum.

### B. Trespassing and "Right to Roam"
*   **Purple Paint Law:** In many US states, purple paint on trees or fence posts has the same legal weight as a "No Trespassing" sign.
*   **BLM and USFS Land:** Much of the American West is public land managed by the Bureau of Land Management. Geothermal sites on these lands are generally accessible, but specific "Area of Critical Environmental Concern" (ACEC) designations may restrict entry.

## 4. Technical Stack for Research
*   **Google Earth Engine (GEE):** For processing large-scale multi-temporal satellite datasets.
*   **QGIS:** An open-source Geographic Information System for layering historical maps, LiDAR data, and personal GPS waypoints.
*   **CalTopo / Gaia GPS:** For field navigation using high-resolution slope-angle shading and private land ownership layers (MapWise/OnX).
