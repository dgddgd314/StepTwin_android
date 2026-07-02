# STEP-Twin Server Contract

The Android app is implemented so the server can be added later without changing the UI or domain logic.

## Current Client Behavior

After a TUG-style sensor measurement finishes, the app:

1. Calculates the local vulnerability vector.
2. Stores the latest result in memory.
3. Attempts to POST the result to the server.
4. Continues successfully even when the server is unavailable.

This is intentional because the server is not implemented yet.

## Base URL

Development default:

```text
http://10.0.2.2:8080/
```

`10.0.2.2` is the Android emulator alias for the host machine.

## Upload Walking Weights

```http
POST /api/v1/weights
Content-Type: application/json
```

### Request Body

```json
{
  "speedWeight": 0.72,
  "turnWeight": 0.41,
  "strengthWeight": 0.83,
  "measuredAtEpochMillis": 1782662400000,
  "sampleCount": 680
}
```

### Field Meaning

- `speedWeight`: Walking speed vulnerability, normalized `0.0..1.0`.
- `turnWeight`: Turning or trunk wobble vulnerability, normalized `0.0..1.0`.
- `strengthWeight`: Sit-to-stand or lower-body strength vulnerability, normalized `0.0..1.0`.
- `measuredAtEpochMillis`: Client-side measurement completion time.
- `sampleCount`: Number of raw sensor samples used in the calculation.

### Expected Success Response

```json
{
  "id": "weight_20260629_0001",
  "status": "ok"
}
```

## Error Handling

The client treats network errors as non-fatal for the hackathon demo.

- Local calculation still succeeds.
- The profile screen still shows the latest vector.
- The measurement screen shows that server sync is pending or unavailable.

## Future Endpoints

These are not implemented in the Android client yet, but are likely next steps:

```http
GET /api/v1/weights/latest
GET /api/v1/routes?origin={originId}&destination={destinationId}
POST /api/v1/routes/recommend
```

For the current demo, route calculation is performed locally with a static graph.
