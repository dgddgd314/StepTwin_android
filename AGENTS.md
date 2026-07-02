# STEP-Twin Project Notes

## Product Direction

STEP-Twin is a senior-friendly personalized walking navigation app. The core demo logic is:

1. Measure a user's walking characteristics with smartphone sensors.
2. Convert the measurement into a vulnerability vector.
3. Combine that vector with route edge risk factors.
4. Recommend the route with the lowest personalized movement cost.

The app does not require hospital EMR or sensitive patient data.

## Hackathon Demo Scope

The demo area is intentionally narrow:

- Cheongnyangni Station
- Gyeongdong Market
- Kyung Hee University Medical Center

The app should focus on a high-quality demo for this corridor instead of covering a full city map.

## Navigation Structure

The app uses a bottom-bar structure with three sections:

1. Gait Test
   - 3-axis sensor-based TUG-style measurement.
   - Produces speed, turn, and strength vulnerability values.
2. Personalized Route
   - Uses the latest walking profile to calculate route edge costs.
   - Demonstrates that different users receive different routes.
3. Walking Profile
   - Shows the latest vulnerability vector and measurement summary.
   - Explains why route recommendations change.

## Android Architecture

Use MVVM with Hilt.

- View
  - Compose UI and Android sensor listener.
  - Sends raw sensor samples to the ViewModel.
  - Does not contain calculation logic.
- ViewModel
  - Owns screen state, recording timer, and flow control.
  - Stores samples during the measurement window.
  - Requests analysis/sync through the repository.
- Domain
  - Pure walking analysis and route-cost algorithms.
  - No Android UI or Context dependency.
- Data
  - Repository, API DTOs, and remote data source.
  - Keeps the latest locally calculated profile.
- DI
  - Hilt modules provide calculators, repositories, Retrofit, and API services.

## Measurement Decision

The first implementation uses a 15-second TUG-style measurement window:

- Linear acceleration is preferred.
- Accelerometer is used as a fallback if linear acceleration is not available.
- Gyroscope is used for turn/wobble estimation.

The initial vulnerability vector is:

```text
V_user = (speedWeight, turnWeight, strengthWeight)
```

All values are normalized to `0.0..1.0`, where higher means more vulnerable.

## Route Cost Decision

Every route edge has environmental risk values:

```text
E_edge = (distance, narrowRisk, stairsRisk, slopeRisk, curbRisk, turnRisk)
```

The route cost is a weighted sum of base walking distance and user-specific risk penalties. Dijkstra is used over a small demo graph.

The demo must support these stories:

- A strength-vulnerable user avoids stairs, curbs, and steep paths even if the route is longer.
- A turn-vulnerable user prefers simpler paths with fewer complex turns.
- The same origin/destination can produce different routes for different walking profiles.

## Current Constraints

- Server implementation is not ready yet.
- The app still calculates and stores the walking profile locally when sync fails.
- Real map SDK integration is out of scope for the first implementation; the route tab uses a curated demo graph.
