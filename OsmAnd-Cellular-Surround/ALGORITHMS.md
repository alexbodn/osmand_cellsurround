# OsmAnd Cellular Surround Algorithms

This document describes the algorithms used by the OsmAnd Cellular Surround app to resolve cell tower locations for map display and to donate new readings back to OpenCelliD.

## 1. Gathering Cell Towers to be on Map

When the user initiates a scan (by clicking **SHOW ON MAP**), the app attempts to determine a central point and gather surrounding cell towers to display in OsmAnd. The process is broken down into the following steps:

### A. Main Tower Resolution
The app first tries to find the exact coordinates for the main tower provided in the `radio,mcc,mnc,lac,cid` input string. It does this via `DataSyncManager.ensureCellTowerExistsAndGet`:

1.  **Local Database Check:** It queries the local Room database (`getCellTower`) for the exact matching `MCC`, `MNC`, and `CID` (ignoring `LAC` as it's not a unique identifier). If found, this tower is returned as the main tower.
2.  **MCC Database Download Fallback:** If the tower isn't found, the app checks if *any* towers exist for that specific `MCC` in the local DB. If the count is 0, it means the user hasn't downloaded the DB for that region yet. It triggers a download and import of the full OpenCelliD CSV for that `MCC`. Afterward, it re-queries the local database for the specific tower.
3.  **Single Cell API Fallback:** If the tower is *still* missing (e.g., it's a very new tower not yet in the OpenCelliD CSV dumps), the app makes a single API request (`OpenCellidApi.getCellLocation`) using the `radio`, `mcc`, `mnc`, `lac`, and `cid` to a fallback location API (e.g., Unwired Labs). If the API successfully resolves the coordinates, the tower is inserted into the local database and used as the main tower.

### B. Fallback Central Point Calculation
If the main tower resolution entirely fails (the API returns null/fails), the flow *does not abort*. Instead, it falls back to a LAC-based average:
- It queries the local database for *all* towers that share the same `MCC`, `MNC`, and `LAC`.
- It calculates the mathematical average of the latitudes and longitudes of these towers to determine a "fallback center" coordinate.
- The scan proceeds using this center coordinate, but no specific tower will be highlighted in green on the resulting map.

### C. Surrounding Towers Retrieval
Once a central point is established (either the exact main tower or the fallback center), the app retrieves the surrounding towers to display:
1.  **Bounding Box Calculation:** Using the selected radius (e.g., 4.0km), it calculates a rough geographic bounding box (`GpxGenerator.calculateBoundingBox`). It assumes 1 degree of latitude is roughly 111km and adjusts the longitude offset based on the cosine of the latitude.
2.  **Database Query:**
    - If the user has entered a custom SQL query in the "CONFIG" tab, that query is executed. The app dynamically substitutes named parameters like `:minLat`, `:maxLat`, `:mcc`, `:lac`, etc., into the query. An `ORDER BY lat, lon` clause is appended automatically if not present.
    - If no custom SQL is provided, it performs a standard geographic query (`getTowersInBoundingBox`) against the local Room database using the calculated bounding box.

### D. GPX Generation and Map Display
Finally, the gathered towers are formatted for OsmAnd:
1.  **Grouping:** The app iterates through all surrounding towers (and the main tower, if found). Towers sharing the *exact* same latitude and longitude are grouped together into a single map waypoint.
2.  **GPX Waypoint formatting:** For each coordinate group, a `<wpt>` tag is created.
    - The descriptions (`<desc>`) of all grouped towers (format: `MCC-MNC-LAC-CID`) are joined with newlines.
    - If a group contains the exact main tower, the waypoint is tagged as `<type>main_tower</type>` and colored green. Otherwise, it is tagged as `<type>surrounding_tower</type>` and colored blue.
3.  **Handoff to OsmAnd:** The GPX file is saved to the cache, temporary URI permissions are granted, and the app uses AIDL to instruct OsmAnd to import the GPX and center the map. The map center defaults to the main tower, the fallback center, or finally the average location of the returned surrounding towers.

---

## 2. Donating a Reading to OpenCelliD

When the user clicks the **DONATE** button, the app securely collects live network data and uploads it to OpenCelliD to help improve their database. To prevent spoofing or submitting bad data, this process bypasses user-editable fields entirely.

### A. Pre-conditions and Live Data Fetching
1.  **Checks:** The app verifies that an API key has been saved and that the necessary location and telephony permissions are granted.
2.  **Live Telephony Fetch:** The app directly calls `TelephonyHelper.getCurrentCellInfo` to read the *live* cellular sensor data from the Android device. It iterates through registered GSM, LTE, or WCDMA cells to extract the active `radio`, `mcc`, `mnc`, `lac/tac`, and `cid`. If it cannot read the live data, the donation is aborted.

### B. GPS Verification
OpenCelliD requires highly accurate GPS coordinates to properly map towers. The app handles this dynamically:
1.  **Last Known Location:** It checks the `LocationManager` for the last known GPS location.
2.  **Accuracy Threshold:** The location is only accepted if it has an accuracy of less than **20 meters** and is less than 30 seconds old.
3.  **Dynamic Wait:** If no such location is available, the app temporarily disables the DONATE button, shows a toast saying "Waiting for reliable GPS (<20m)...", and requests active GPS updates.
4.  If a location meeting the <20m accuracy requirement is received within 10 seconds, the donation proceeds. Otherwise, it times out and aborts.

### C. Network Submission
Once valid live cellular data and highly accurate GPS coordinates are obtained, the app initiates the network request via `OpenCellidApi.donateData`:
1.  **Formatting:** The `radio` type is mapped to OpenCelliD's expected `act` parameter (e.g., "lte" -> "LTE", "umts" -> "UMTS").
2.  **Request:** An HTTP GET request is made to `https://opencellid.org/measure/add` containing the user's API key, latitude, longitude, MCC, MNC, LAC, CID, and the network technology (act).
3.  **Result:** The app waits for the server response and surfaces a success or failure Toast to the user, while logging the transaction in the Status tab.