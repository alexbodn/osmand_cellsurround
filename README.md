# OsmAnd Cellular Surround

OsmAnd Cellular Surround is an Android application that serves as an external plugin for the popular OsmAnd maps app. It leverages cell tower location data from OpenCelliD to resolve and map cell towers surrounding the user, providing offline mapping of network coverage and towers right in OsmAnd.

## Features

- **Cell Tower Mapping**: Locate and display nearby cell towers on the map.
- **Offline Data**: Uses a local Room SQLite database to cache cell tower data for offline queries.
- **Integration with OsmAnd**: Automatically exports map markers and opens them in OsmAnd using AIDL IPC.
- **OpenCelliD Support**: Fetches missing tower data from OpenCelliD API.
- **Data Donation**: Allows users to contribute live cell tower measurements back to OpenCelliD directly from the application.
- **Custom SQL Queries**: Offers an advanced internal SQL database browser to explore the local OpenCelliD cache and build custom queries.

## Setup and Requirements

- The app requires Android 9 (API 28) or higher.
- In order to send waypoints automatically to OsmAnd, you must have the **cellular surround plugin** enabled within your OsmAnd application.

## How to use

1. Launch OsmAnd Cellular Surround.
2. The app will fetch the connected cell tower and surrounding ones based on your network.
3. Click "SHOW ON MAP" to export the generated GPX data directly into OsmAnd, drawing waypoints where cell towers are located.
4. If you have valid network data and GNSS fix (accuracy < 20m), use "DONATE DATA" to contribute your live telemetry back to OpenCelliD.

## Licensing and Attributions

This project relies on data and libraries from open source projects:
- **OpenCelliD**: The cell tower data is provided by the OpenCelliD Project under CC BY-SA 4.0.
- **OsmAnd AIDL Library**: Used for OsmAnd communication, under the MIT License.

See the `LICENSE` and `NOTICE` files for details.
