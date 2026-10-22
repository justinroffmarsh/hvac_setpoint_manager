definition(
    name: "HVAC Setpoint Override",
    namespace: "roffmarsh",
    author: "Justin Roff-Marsh",
    description: "Applies mode-based HVAC setpoints and holds a manual change for a set time",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/justinroffmarsh/hvac_setpoint_manager/main/apps/hvac-setpoint-override.groovy"
)

preferences {
    page(name: "mainPage", title: "HVAC Setpoint Override", install: true, uninstall: true) {
        section("Thermostats") {
            input "downstairsThermostat", "capability.thermostat", title: "Downstairs thermostat", required: true, multiple: false
            input "upstairsThermostat", "capability.thermostat", title: "Upstairs thermostat", required: false, multiple: false
            paragraph "Heat or cool is read from each thermostat. If a thermostat is off, it is left alone unless you choose a fallback below."
        }

        section("Setpoints") {
            input "heatPoint", "decimal", title: "Heat point (home)", required: true
            input "heatPointAway", "decimal", title: "Heat point (away)", required: true
            input "coolPoint", "decimal", title: "Cool point (home)", required: true
            input "coolPointAway", "decimal", title: "Cool point (away)", required: true
            paragraph "Away setpoints are used when the location mode name contains \"away\"."
        }

        section("If the thermostat is off") {
            input "downstairsOffMode", "enum", title: "Downstairs",
                options: ["do nothing": "Do nothing", "heat": "Treat as heat", "cool": "Treat as cool"],
                defaultValue: "do nothing", required: true
            input "upstairsOffMode", "enum", title: "Upstairs",
                options: ["do nothing": "Do nothing", "heat": "Treat as heat", "cool": "Treat as cool"],
                defaultValue: "do nothing", required: true
        }

        section("Behavior") {
            input "checkInterval", "enum", title: "Check interval",
                options: ["5", "10", "12", "15", "30"], defaultValue: "12", required: true
            input "overrideHours", "number", title: "Manual override (hours)", defaultValue: 4, required: true, range: "1..24"
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
            input "applyNow", "button", title: "Apply setpoints now"
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
    if (logEnable) log.debug "Initializing. Interval ${checkInterval} min, override ${overrideHours} h"

    if (downstairsThermostat) subscribe(downstairsThermostat, "thermostatMode", thermostatModeHandler)
    if (upstairsThermostat) subscribe(upstairsThermostat, "thermostatMode", thermostatModeHandler)
    subscribe(location, "mode", locationModeHandler)

    def minutes = (checkInterval ?: "12") as int
    schedule("0 */${minutes} * ? * *", "checkAndSetSetpoints")
    runIn(5, "checkAndSetSetpoints")
}

def appButtonHandler(btn) {
    if (btn == "applyNow") forceApply("button")
}

def thermostatModeHandler(evt) {
    clearHold(evt.device)
    if (logEnable) log.debug "Thermostat mode changed on ${evt.device.displayName}; override cleared"
    runIn(2, "checkAndSetSetpoints")
}

def locationModeHandler(evt) {
    forceApply("location ${evt.value}")
}

def forceApply(reason) {
    log.info "HVAC force-apply (${reason})"
    state.forceApply = true
    if (downstairsThermostat) clearHold(downstairsThermostat)
    if (upstairsThermostat) clearHold(upstairsThermostat)
    checkAndSetSetpoints()
}

def checkAndSetSetpoints() {
    try {
        if (downstairsThermostat) processThermostat(downstairsThermostat, downstairsOffMode)
        if (upstairsThermostat) processThermostat(upstairsThermostat, upstairsOffMode)
    } finally {
        state.forceApply = false
    }
}

def clearHold(device) {
    if (!device) return
    state["overrideUntil_${device.id}"] = 0L
    state["lastCommanded_${device.id}"] = null
}

def processThermostat(thermostat, String offMode) {
    if (!thermostat) return

    def deviceMode = thermostat.currentValue("thermostatMode")?.toString()?.toLowerCase()
    def isHeat = null
    if (deviceMode == "heat" || deviceMode == "emergency heat") isHeat = true
    else if (deviceMode?.contains("cool")) isHeat = false
    else if (offMode == "heat") isHeat = true
    else if (offMode == "cool") isHeat = false
    else {
        log.info "HVAC ${thermostat.displayName}: mode is ${deviceMode ?: 'unknown'}; not changing setpoint"
        return
    }

    def isAway = location.mode?.toLowerCase()?.contains("away") ?: false
    def target = isHeat ? (isAway ? heatPointAway : heatPoint) : (isAway ? coolPointAway : coolPoint)
    if (target == null) {
        log.warn "HVAC ${thermostat.displayName}: enter the setpoints in the app and click Done"
        return
    }
    def numericTarget = (target as BigDecimal).intValue()

    def actual = thermostat.currentValue("thermostatSetpoint")
    def id = thermostat.id
    def overrideKey = "overrideUntil_${id}"
    def lastKey = "lastCommanded_${id}"
    def seenKey = "baselined_${id}"
    def overrideUntil = (state[overrideKey] ?: 0) as long

    if (state.forceApply) {
        writeSetpoint(thermostat, isHeat, numericTarget, actual, deviceMode, "forced")
        state[seenKey] = true
        state[overrideKey] = 0L
        state[lastKey] = numericTarget
        return
    }

    if (now() < overrideUntil) {
        log.info "HVAC ${thermostat.displayName}: hold until ${new Date(overrideUntil)} (actual=${actual}, target=${numericTarget})"
        return
    }

    if (!state[seenKey]) {
        state[seenKey] = true
        state[lastKey] = actual == null ? null : (actual as BigDecimal).intValue()
        if (actual != null && !sameTemp(actual, numericTarget)) {
            startHold(thermostat, overrideKey, lastKey, actual, numericTarget)
        }
        return
    }

    def lastCommanded = state[lastKey]
    if (lastCommanded != null && actual != null
            && !sameTemp(actual, lastCommanded)
            && !sameTemp(actual, numericTarget)) {
        startHold(thermostat, overrideKey, lastKey, actual, numericTarget)
        return
    }

    if (actual == null || !sameTemp(actual, numericTarget)) {
        writeSetpoint(thermostat, isHeat, numericTarget, actual, deviceMode, "scheduled")
        state[lastKey] = numericTarget
    }
}

def writeSetpoint(thermostat, boolean isHeat, int cmd, actual, deviceMode, why) {
    try {
        if (isHeat) thermostat.setHeatingSetpoint(cmd)
        else thermostat.setCoolingSetpoint(cmd)
        log.info "HVAC ${thermostat.displayName}: ${why} set ${isHeat ? 'heating' : 'cooling'} to ${cmd} (actual=${actual}, device=${deviceMode}, location=${location.mode})"
    } catch (e) {
        log.error "HVAC failed to set ${thermostat.displayName}: ${e.message}"
    }
}

def startHold(thermostat, String overrideKey, String lastKey, actual, target) {
    def hours = (overrideHours ?: 4) as int
    state[overrideKey] = now() + (hours * 60L * 60L * 1000L)
    state[lastKey] = null
    log.info "HVAC ${thermostat.displayName}: hold ${actual} (target ${target}) until ${new Date(state[overrideKey])}"
}

def sameTemp(a, b) {
    if (a == null || b == null) return false
    return (((a as BigDecimal) - (b as BigDecimal)).abs() < 0.2)
}
