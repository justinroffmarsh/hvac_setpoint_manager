definition(
    name: "HVAC Setpoint Override",
    namespace: "roffmarsh",
    author: "Justin Roff-Marsh",
    description: "Applies mode-based HVAC setpoints with a 4-hour manual override that survives until the next scheduled cycle or a mode change",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/yourusername/hvac-setpoint-override/main/apps/hvac-setpoint-override.groovy"
)

preferences {
    page(name: "mainPage", title: "HVAC Setpoint Override", install: true, uninstall: true) {
        section("Thermostats") {
            input "downstairsThermostat", "capability.thermostat", title: "Downstairs Thermostat", required: true, multiple: false
            input "upstairsThermostat", "capability.thermostat", title: "Upstairs Thermostat", required: false, multiple: false
        }

        section("Hub Variable Names") {
            input "downstairsModeVar", "text", title: "Downstairs HVAC Mode Variable", defaultValue: "DownstairsHVACMode", required: true
            input "upstairsModeVar", "text", title: "Upstairs HVAC Mode Variable", defaultValue: "UpstairsHVACMode", required: false
            paragraph "Setpoint variables are shared between both thermostats."
            input "heatPointVar", "text", title: "Heat Point (home)", defaultValue: "HeatPoint", required: true
            input "heatPointAwayVar", "text", title: "Heat Point (away)", defaultValue: "HeatPointCat", required: true
            input "coolPointVar", "text", title: "Cool Point (home)", defaultValue: "CoolPoint", required: true
            input "coolPointAwayVar", "text", title: "Cool Point (away)", defaultValue: "CoolPointCat", required: true
        }

        section("Behavior") {
            input "checkInterval", "enum", title: "Check interval", options: ["5 minutes", "10 minutes", "12 minutes", "15 minutes", "30 minutes"], defaultValue: "12 minutes", required: true
            input "overrideHours", "number", title: "Manual override duration (hours)", defaultValue: 4, required: true, range: "1..24"
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
        }
    }
}

def installed() {
    log.info "Installed HVAC Setpoint Override"
    initialize()
}

def updated() {
    log.info "Updated HVAC Setpoint Override"
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    if (logEnable) log.debug "Initializing — interval: ${checkInterval}, override: ${overrideHours}h"

    if (downstairsThermostat) {
        subscribe(downstairsThermostat, "thermostatMode", modeChangedHandler)
    }
    if (upstairsThermostat) {
        subscribe(upstairsThermostat, "thermostatMode", modeChangedHandler)
    }

    def minutes = checkInterval.replace(" minutes", "").toInteger()
    schedule("0 */${minutes} * ? * *", "checkAndSetSetpoints")

    // Run once now
    runIn(5, "checkAndSetSetpoints")
}

def modeChangedHandler(evt) {
    def device = evt.device
    def key = overrideKeyFor(device)
    state[key] = now() - 1000
    if (logEnable) log.debug "Mode changed on ${device.displayName} → cleared override"
}

def checkAndSetSetpoints() {
    if (downstairsThermostat) {
        processThermostat(downstairsThermostat, downstairsModeVar, overrideKeyFor(downstairsThermostat))
    }
    if (upstairsThermostat) {
        processThermostat(upstairsThermostat, upstairsModeVar, overrideKeyFor(upstairsThermostat))
    }
}

private String overrideKeyFor(device) {
    return (device?.id == downstairsThermostat?.id) ? "overrideUntilDownstairs" : "overrideUntilUpstairs"
}

def processThermostat(thermostat, modeVarName, overrideKey) {
    if (!thermostat || !modeVarName) return

    def modeRaw = getHubVariable(modeVarName)
    def currentMode = modeRaw?.toString()?.trim()?.toLowerCase()
    def isAway = location.mode?.toLowerCase()?.contains("away") ?: false
    def isHeat = (currentMode == "heat")

    def target = null
    if (isHeat) {
        target = isAway ? getHubVariable(heatPointAwayVar) : getHubVariable(heatPointVar)
    } else {
        target = isAway ? getHubVariable(coolPointAwayVar) : getHubVariable(coolPointVar)
    }

    if (target == null) {
        if (logEnable) log.warn "No target setpoint for ${thermostat.displayName} (mode=${currentMode}, away=${isAway})"
        return
    }

    target = target as BigDecimal
    def actual = thermostat.currentValue("thermostatSetpoint") as BigDecimal
    def overrideUntil = (state[overrideKey] ?: 0) as Long

    // Start override only when none is active and values differ
    if (now() >= overrideUntil && actual != null && actual != target) {
        state[overrideKey] = now() + ((overrideHours ?: 4) * 60 * 60 * 1000)
        if (logEnable) log.debug "${thermostat.displayName}: manual change detected (actual=${actual}, target=${target}). Override until ${new Date(state[overrideKey])}"
        return
    }

    // Apply only if override has expired
    if (now() >= overrideUntil) {
        try {
            if (isHeat) {
                thermostat.setHeatingSetpoint(target)
            } else {
                thermostat.setCoolingSetpoint(target)
            }
            if (logEnable) log.debug "${thermostat.displayName}: applied ${isHeat ? 'heating' : 'cooling'} setpoint ${target}"
        } catch (e) {
            log.error "Failed to set setpoint on ${thermostat.displayName}: ${e}"
        }
    } else if (logEnable) {
        log.debug "${thermostat.displayName}: override active until ${new Date(overrideUntil)}, skipping"
    }
}

private def getHubVariable(String varName) {
    if (!varName) return null
    try {
        def gv = getGlobalVar(varName)
        return gv?.value
    } catch (e) {
        if (logEnable) log.warn "Could not read hub variable '${varName}': ${e.message}"
        return null
    }
}
