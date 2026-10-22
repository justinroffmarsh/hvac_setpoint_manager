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
        }

        section("Hub variable names") {
            input "downstairsModeVar", "text", title: "Downstairs HVAC mode", defaultValue: "DownstairsHVACMode", required: true
            input "upstairsModeVar", "text", title: "Upstairs HVAC mode", defaultValue: "UpstairsHVACMode", required: false
            paragraph "Setpoint variables are shared by both thermostats. Mode values should be Heat or Cool."
            input "heatPointVar", "text", title: "Heat point (home)", defaultValue: "HeatPoint", required: true
            input "heatPointAwayVar", "text", title: "Heat point (away)", defaultValue: "HeatPointCat", required: true
            input "coolPointVar", "text", title: "Cool point (home)", defaultValue: "CoolPoint", required: true
            input "coolPointAwayVar", "text", title: "Cool point (away)", defaultValue: "CoolPointCat", required: true
        }

        section("Behavior") {
            input "checkInterval", "enum", title: "Check interval",
                options: ["5", "10", "12", "15", "30"], defaultValue: "12", required: true
            input "overrideHours", "number", title: "Manual override (hours)", defaultValue: 4, required: true, range: "1..24"
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
    if (logEnable) log.debug "Initializing. Interval ${checkInterval} min, override ${overrideHours} h"

    def names = [downstairsModeVar, upstairsModeVar, heatPointVar, heatPointAwayVar, coolPointVar, coolPointAwayVar].findAll { it }
    if (names) {
        try {
            addInUseGlobalVar(names)
        } catch (e) {
            log.warn "Could not mark hub variables in use: ${e.message}"
        }
    }

    if (downstairsThermostat) subscribe(downstairsThermostat, "thermostatMode", thermostatModeHandler)
    if (upstairsThermostat) subscribe(upstairsThermostat, "thermostatMode", thermostatModeHandler)
    subscribe(location, "mode", locationModeHandler)

    def minutes = (checkInterval ?: "12") as int
    schedule("0 */${minutes} * ? * *", "checkAndSetSetpoints")
    runIn(5, "checkAndSetSetpoints")
}

def thermostatModeHandler(evt) {
    def key = "overrideUntil_${evt.device.id}"
    state[key] = 0L
    state.remove("lastCommanded_${evt.device.id}")
    if (logEnable) log.debug "Thermostat mode changed on ${evt.device.displayName}; override cleared"
    runIn(2, "checkAndSetSetpoints")
}

def locationModeHandler(evt) {
    if (logEnable) log.debug "Location mode is ${evt.value}"
    checkAndSetSetpoints()
}

def checkAndSetSetpoints() {
    if (downstairsThermostat) processThermostat(downstairsThermostat, downstairsModeVar)
    if (upstairsThermostat && upstairsModeVar) processThermostat(upstairsThermostat, upstairsModeVar)
}

def processThermostat(thermostat, String modeVarName) {
    if (!thermostat || !modeVarName) return

    def modeRaw = readVar(modeVarName)
    def currentMode = modeRaw?.toString()?.trim()?.toLowerCase()
    def isHeat = currentMode == "heat"
    def isAway = location.mode?.toLowerCase()?.contains("away") ?: false

    def target = isHeat
        ? readVar(isAway ? heatPointAwayVar : heatPointVar)
        : readVar(isAway ? coolPointAwayVar : coolPointVar)

    if (target == null) {
        log.warn "No target setpoint for ${thermostat.displayName} (mode=${currentMode}, away=${isAway})"
        return
    }
    target = target as BigDecimal

    def actual = thermostat.currentValue("thermostatSetpoint")
    if (actual != null) actual = actual as BigDecimal

    def id = thermostat.id
    def overrideKey = "overrideUntil_${id}"
    def lastKey = "lastCommanded_${id}"
    def seenKey = "baselined_${id}"
    def overrideUntil = (state[overrideKey] ?: 0) as long

    if (now() < overrideUntil) {
        if (logEnable) log.debug "${thermostat.displayName}: hold until ${new Date(overrideUntil)}"
        return
    }

    // First sighting: if the thermostat is already off the calculated setpoint,
    // treat that as an existing manual hold rather than overwriting it.
    if (!state[seenKey]) {
        state[seenKey] = true
        state[lastKey] = actual
        if (actual != null && !sameTemp(actual, target)) {
            startHold(thermostat, overrideKey, lastKey, actual, target)
        }
        return
    }

    def lastCommanded = state[lastKey]
    if (lastCommanded != null && actual != null
            && !sameTemp(actual, lastCommanded as BigDecimal)
            && !sameTemp(actual, target)) {
        startHold(thermostat, overrideKey, lastKey, actual, target)
        return
    }

    if (actual == null || !sameTemp(actual, target)) {
        try {
            if (isHeat) thermostat.setHeatingSetpoint(target)
            else thermostat.setCoolingSetpoint(target)
            state[lastKey] = target
            if (logEnable) log.debug "${thermostat.displayName}: set ${isHeat ? 'heating' : 'cooling'} to ${target}"
        } catch (e) {
            log.error "Failed to set ${thermostat.displayName}: ${e.message}"
        }
    }
}

private void startHold(thermostat, String overrideKey, String lastKey, actual, target) {
    def hours = (overrideHours ?: 4) as int
    state[overrideKey] = now() + (hours * 60L * 60L * 1000L)
    state[lastKey] = null
    if (logEnable) {
        log.debug "${thermostat.displayName}: hold ${actual} (target ${target}) until ${new Date(state[overrideKey])}"
    }
}

private boolean sameTemp(a, b) {
    if (a == null || b == null) return false
    return ((a as BigDecimal) - (b as BigDecimal)).abs() < 0.2
}

private def readVar(String varName) {
    if (!varName) return null
    try {
        return getGlobalVar(varName)?.value
    } catch (e) {
        log.warn "Could not read hub variable '${varName}': ${e.message}"
        return null
    }
}
