# HVAC Setpoint Override

A Hubitat app that applies heating/cooling setpoints based on hub mode and custom hub variables, while allowing a temporary manual override (default 4 hours) that is automatically cleared on thermostat mode changes.

## Features

- Supports one or two thermostats (Downstairs / Upstairs)
- Reads HVAC mode and setpoints from Hub Variables
- Detects manual setpoint changes and holds them for a configurable duration
- Clears the hold immediately when the thermostat’s own mode changes
- Configurable check interval (5–30 minutes)
- Debug logging toggle

## Installation

### Via Hubitat Package Manager (recommended)

1. Install [Hubitat Package Manager](https://github.com/HubitatCommunity/hubitatpackagemanager) if you don’t already have it.
2. Open HPM → Add a Repository → paste the raw URL of this repo’s `packageManifest.json`.
3. Search for “HVAC Setpoint Override” and install.

### Manual

1. On your hub, enable **Settings → Show advanced/developer options**.
2. Go to **Apps Code → + New App**.
3. Click the three-dot menu → **Import**, paste the raw URL of `apps/hvac-setpoint-override.groovy`, then Save.
4. Go to **Apps → + Add User App** and select the app.

## Configuration

1. Select your downstairs (and optionally upstairs) thermostat.
2. Confirm the Hub Variable names match the ones you already use for mode and setpoints.
3. Choose check interval and override duration.
4. Enable debug logging while testing.

## How the override works

- Every check cycle the app calculates the expected setpoint from the current HVAC mode variable and whether the hub is in an Away mode.
- If the thermostat’s current setpoint differs and no override is active, it starts a new override timer.
- While the timer is running the app leaves the thermostat alone.
- When the timer expires the next cycle writes the calculated setpoint again.
- A change to the thermostat’s `thermostatMode` attribute immediately expires the override.

## Requirements

- Hubitat Elevation 2.3+
- Hub Variables containing the mode strings and numeric setpoints
- Thermostats that expose `thermostatMode`, `thermostatSetpoint`, `setHeatingSetpoint`, and `setCoolingSetpoint`

## License

MIT
