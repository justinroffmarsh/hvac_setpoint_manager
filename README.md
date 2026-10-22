# HVAC Setpoint Override

Hubitat app that replaces the downstairs/upstairs webCoRE setpoint piston.

It writes the heating or cooling setpoint from Hub Variables, based on the HVAC mode variable and whether the hub location mode contains "away". A manual change on the thermostat is left alone for a configurable hold (default 4 hours), then written back. Changing the thermostat's own mode ends the hold immediately.

## Install

This repository is private. Hubitat's Import button and Hubitat Package Manager can only fetch a **public** raw URL. Either make the repo public, or paste the Groovy in by hand:

1. Settings, then enable **Show advanced/developer options**.
2. **Apps Code**, then **+ New App**.
3. Paste the contents of [apps/hvac-setpoint-override.groovy](apps/hvac-setpoint-override.groovy) and Save.
4. **Apps**, then **+ Add User App**, and select **HVAC Setpoint Override**.

Once the repo is public, HPM can use:

`https://raw.githubusercontent.com/justinroffmarsh/hvac_setpoint_manager/main/packageManifest.json`

## Settings

- Downstairs thermostat (required) and upstairs thermostat (optional).
- Hub variable names. Defaults match the existing webCoRE globals:
  - `DownstairsHVACMode`, `UpstairsHVACMode`
  - `HeatPoint`, `HeatPointCat`
  - `CoolPoint`, `CoolPointCat`
- Mode variable values should be `Heat` or `Cool`.
- Away setpoints are used when the location mode name contains "away" (for example `Away: Day` and `Away: Night`).
- Check interval and hold length.

## Behavior

- On the first run, if the thermostat is already off the calculated setpoint, that difference is treated as a hold so the app does not overwrite it immediately.
- A later manual change starts one hold. Later checks do not extend it.
- When the hold ends, the calculated setpoint is written.
- A change to `thermostatMode` clears the hold and the new mode's setpoint is applied.
- A location-mode change (home versus away) updates the setpoint on the next check, but does not cancel an active hold.

## Requirements

- Hubitat Elevation 2.3 or newer
- Hub Variables for mode and setpoints
- Thermostats with `thermostatMode`, `thermostatSetpoint`, `setHeatingSetpoint`, and `setCoolingSetpoint`

## License

MIT
