# HVAC Setpoint Override

Hubitat app that replaces the downstairs/upstairs webCoRE setpoint piston.

Setpoints are entered in the app. Heat or cool is read from each thermostat. A location mode whose name contains "away" selects the away setpoints. A manual change on the thermostat is left alone for a configurable hold (default 4 hours), then written back. A thermostat mode change or a location mode change ends the hold and applies the new setpoint.

## Install

Hubitat Package Manager, Install, From a URL:

`https://raw.githubusercontent.com/justinroffmarsh/hvac_setpoint_manager/main/packageManifest.json`

Or import the Groovy directly from:

`https://raw.githubusercontent.com/justinroffmarsh/hvac_setpoint_manager/main/apps/hvac-setpoint-override.groovy`

## Settings

- Downstairs thermostat (required) and upstairs thermostat (optional).
- Four setpoints: heat home, heat away, cool home, cool away.
- If a thermostat is off, choose Do nothing, Treat as heat, or Treat as cool.
- Check interval and hold length.

## Behavior

- On the first run, if the thermostat is already off the calculated setpoint, that difference is treated as a hold so the app does not overwrite it immediately.
- A later manual change starts one hold. Later checks do not extend it.
- When the hold ends, the calculated setpoint is written.
- A thermostat mode change clears the hold and applies the setpoint for the new mode.
- A location mode change clears the hold and applies the home or away setpoint.

## Requirements

- Hubitat Elevation 2.3 or newer
- Thermostats with `thermostatMode`, `thermostatSetpoint`, `setHeatingSetpoint`, and `setCoolingSetpoint`

## License

MIT
