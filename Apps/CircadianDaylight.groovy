/**
*	Hubitat Circadian Daylight 0.77
*
*	Author: 
*		Adam Kempenich 
*
*	Documentation:  https://community.hubitat.com/t/release-app-circadian-daylight-port/
*	
*	Forked from:
*  		SmartThings Circadian Daylight v. 2.6
*		https://github.com/KristopherKubicki/smartapp-circadian-daylight/
*
*  Changelog:
*	0.77 (Sep 26 2019)
*		- Removed sunrise/sunset code
*		- Added ability to turn on/off debug logging
*		- Made Color Temperature time fields optional
*		- Other stuff...
*	0.76 (Sep 26 2019)
*		- Backed up all changes to my local Hubitat developed code
*	0.75 (May 14 2019)
*		- Fixed warmCT / coldCT order on percentage function call
*		- Added configurable refresh interval setting
*		- Added randomization to scheudling seconds to spread out multiple instances executing
*		- Only call getGraduatedCT if there is a CT bulb to set
*		- Fixed getPercentageValue function to use "now()" instead of setting currentTime variable once
*		- Cleaned up more logging and changed leading spaces to tabs for indentation
*
* 	To-Do:
*		- Add number verification
*		- Add logDebug method
*		- Add brightness max/min overrides
* 		- Add custom zip code
*/

definition(
	name: "Circadian Daylight",
	namespace: "circadianDaylight",
	author: "Adam Kempenich",
	description: "Sync your color changing lights and dimmers with natural daylight hues to improve your cognitive functions and restfulness.",
	category: "Green Living",
	iconUrl: "",
	iconX2Url: ""
)

preferences {
	section("Thank you for installing Circadian Daylight! This application dims and adjusts the color temperature of your lights to match the state of the day, which has been proven to aid in cognitive functions and restfulness. The default options are well suited for most users, but feel free to tweak accordingly!") {
	}
	section("Control these bulbs; Select each bulb only once") {
		input "ctbulbs", "capability.colorTemperature", title: "Which Temperature Changing Bulbs?", multiple:true, required: false
		input "dimmers", "capability.switchLevel", title: "Which Dimmers?", multiple:true, required: false
	}
    section("Active in these modes?") {
		input "activeModes", "mode", title: "Which modes do you want this app enabled for?", multiple:true, required: false
	}
	section("What are your 'Sleep' modes? The modes you pick here will dim your lights and filter light to a softer, yellower hue to help you fall asleep easier. These modes MUST be included in the active modes above. Protip: You can pick 'Nap' modes as well!") {
		input "smodes", "mode", title: "What are your Sleep modes?", multiple:true, required: false
	}
	section("Override Constant Brightness (default) with Dynamic Brightness? If you'd like your lights to dim with the evening, override this option. Most people don't like it, but it can look good in some settings.") {
		input "dbright","bool", title: "On or off?", required: false
	}
	section("Disable Circadian Daylight when the following switches are on:") {
		input "dswitches","capability.switch", title: "Switches", multiple:true, required: false
	}

	section("Color Temperature Overrides"){
		input "coldCTOverride", "number", title: "Cold White Temperature", required: false
		input "warmCTOverride", "number", title: "Warm White Temperature", required: false
	}
	section("Brightness Overrides?") {
		input "maxBrightnessOverride","number", title: "Max Brightness Override", required: false
		input "minBrightnessOverride","number", title: "Min Brightness Override", required: false
	}
	section("Time to Start Brightening / Dimming?") {
		input "brightenTimeStart", "time", title: "Start Brightening At", required: true
		input "brightenTimeEnd", "time", title: "End Brightening At", required: true
		input "dimTimeStart", "time", title: "Start Dimming At", required: true
		input "dimTimeEnd", "time", title: "End Dimming At", required: true
	}
	section("Time to start Cooling / Warming CT?") {
		input "coolingTimeStart", "time", title: "Start Cooling At", required: false
		input "coolingTimeEnd", "time", title: "End Cooling At", required: false
		input "warmingTimeStart", "time", title: "Start Warming At", required: false
		input "warmingTimeEnd", "time", title: "End Warming At", required: false
	}
	section("Refresh interval?") {
		input "refreshInterval", "number", title: "How often to refresh the brightness / CT state? (Default 10 minutes)", required: false
	}
	section("Enable Debug Logging?") {
		input "debugLogEnabled", "bool", title: "On or off?", required: false
	}
}

def installed() {
	unsubscribe()
	unschedule()
	initialize()
}

def updated() {
	unsubscribe()
	unschedule()
	initialize()
}

def logDebug(logString) {
    if (debugLogEnabled) {
        log.debug(logString)
    }
}

private def initialize() {
	logDebug("initialize() with settings: ${settings}")
	def randomSecs = Math.abs(new Random().nextInt() % 60)
	if(ctbulbs) { subscribe(ctbulbs, "switch.on", modeHandler) }
	if(dimmers) { subscribe(dimmers, "switch.on", modeHandler) }
	if(dswitches) { subscribe(dswitches, "switch.off", modeHandler) }
	subscribe(location, "mode", modeHandler)
	
	refreshInterval = settings.refreshInterval == null || settings.refreshInterval == "" ? 10 : settings.refreshInterval
	logDebug("refreshInterval: $refreshInterval")
	def scheduleStr = "$randomSecs */$refreshInterval * * * ?"
	logDebug("scheduleStr: $scheduleStr")
	schedule(scheduleStr, modeHandler)
	subscribe(app,modeHandler)
	// rather than schedule a cron entry, fire a status update a little bit in the future recursively
	scheduleTurnOn()
}

private def getPercentageValue(startTime, endTime, minValue, maxValue, remain) {
	 percentThrough = ((now() - startTime.time) / (endTime.time - startTime.time))
	// log.debug "remain: $remain"
	// log.debug "orig percentThrough: $percentThrough"
	 if (remain) {
		// log.debug "remain is true!"
	 	percentThrough = 1 - percentThrough
	 }
	 // log.debug "percentThrough: $percentThrough"
	 return (percentThrough * (maxValue - minValue)) + minValue
}

def scheduleTurnOn() {
	def int iterRate = 20
	
	def runTime = new Date(now() + 60*15*1000)
	
	// log.debug "checking... ${runTime.time} : $runTime. state.nextTime is ${state.nextTime}"
	if(state.nextTime != runTime.time) {
		state.nextTime = runTime.time
		logDebug("Scheduling next step at: $runTime :: ${state.nextTime}")
		runOnce(runTime, modeHandler)
	}
}


// Poll all bulbs, and modify the ones that differ from the expected state
def modeHandler(evt) {
	// log.debug "modeHandler called"
	for (dswitch in dswitches) {
		if(dswitch.currentSwitch == "on") {
			return
		}
	}
	// log.debug "Mode: $location.mode"
	// log.debug "activeModes: ${settings.activeModes}"
	if (settings.activeModes && !(location.mode in settings.activeModes)) {
		// log.debug "Mode $location.mode not in activeModes ${settings.activeModes}, exiting."
		return
	}
	
	// log.debug "modeHandler getGraduatedBrightness()"
	def bright = getGraduatedBrightness()
	logDebug("bright: $bright")
	def ct = null
	
	if (ctbulbs != null && ctbulbs.size() > 0 && checkIfCtUsed()) {
		ct = getGraduatedCT()
		logDebug("ct: $ct")
	}
	
	for(ctbulb in ctbulbs) {
		// log.debug "modeHandler ctbulb in ctbulbs"
		if(ctbulb.currentValue("switch") == "on") {
			if((settings.dbright == true || location.mode in settings.smodes) && ctbulb.currentValue("level") != bright) {
				ctbulb.setLevel(bright)
			}
			if(ctbulb.currentValue("colorTemperature") != ct) {
				ctbulb.setColorTemperature(ct)
			}
		}
	}
	for(dimmer in dimmers) {
		// log.debug "modeHandler dimmer in dimmers"
		if(dimmer.currentValue("switch") == "on") {
			if(dimmer.currentValue("level") != bright) {
				dimmer.setLevel(bright)
			}
		}
	}
	
	// log.debug "modeHandler scheduleTurnOn"
	scheduleTurnOn()
}

def checkIfCtUsed() {
    if (settings.coolingTimeStart != null && settings.coolingTimeStart != ""
        && settings.coolingTimeEnd != null && settings.coolingTimeEnd != ""
        && settings.warmingTimeStart != null && settings.warmingTimeStart != ""
        && settings.warmingTimeEnd != null && settings.warmingTimeEnd != ""
        && settings.coolCTOverride != null && settings.coolCTOverride != ""
        && settings.warmCTOverride != null && settings.warmCTOverride != "") {
            return true
        }
        return false
}

def getGraduatedCT() {
	def coolingStart = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", settings.coolingTimeStart)
	def coolingEnd = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", settings.coolingTimeEnd)
	def warmingStart = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", settings.warmingTimeStart)
	def warmingEnd = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", settings.warmingTimeEnd)
	
	def int colorTemp = settings.coldCTOverride == null || settings.coldCTOverride == "" ? 2200 : settings.coldCTOverride
	def int coldCT = settings.coldCTOverride == null || settings.coldCTOverride == "" ? 6500 : settings.coldCTOverride
	def int warmCT = settings.warmCTOverride == null || settings.warmCTOverride == "" ? 2200 : settings.warmCTOverride

	def currentTime = now()

	if (currentTime < coolingStart.time) {
		// log.debug "currentTime before coolingStart!"
		colorTemp = warmCT
	}
	else if (currentTime >= coolingStart.time && currentTime <= coolingEnd.time) {
		// log.debug "currentTime between coolingStart and coolingEnd!"
		colorTemp = getPercentageValue(coolingStart, coolingEnd, warmCT, coldCT, false)
	}
	else if (currentTime > coolingEnd.time && currentTime < warmingStart.time) {
		// log.debug "currentTime between coolingEnd and warmingStart!"
		colorTemp = coldCT
	}
	else if (currentTime >= warmingStart.time && currentTime <= warmingEnd.time) {
		// log.debug "currentTime between warmingStart and warmingEnd!"
		colorTemp = getPercentageValue(warmingStart, warmingEnd, warmCT, coldCT, true)
	}
	else if (currentTime > warmingEnd.time) {
		// log.debug "currentTime after warmingEnd!"
		colorTemp = warmCT
	}
	else {
		log.error "currentTime didn't fit into any CT bucket!"
	}
	
	return Math.round(colorTemp)
}

def getGraduatedBrightness() {
	// log.debug "Calling getGraduatedBrightness"
	def brightenStart = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", settings.brightenTimeStart)
	def brightenEnd = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", settings.brightenTimeEnd)
	def dimStart = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", settings.dimTimeStart)
	def dimEnd = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", settings.dimTimeEnd)

	def int maxBrightness = settings.maxBrightnessOverride == null || settings.maxBrightnessOverride == "" ? 100 : settings.maxBrightnessOverride
	def int minBrightness = settings.minBrightnessOverride == null || settings.minBrightnessOverride == "" ? 1 : settings.minBrightnessOverride
	// def int brightnessRange = maxBrightness - minBrightness

	def brightness = 100

	def currentTime = now()
	// log.debug "currentTime: $currentTime, brightenStart: $brightenStart, brightenEnd: $brightenEnd, dimStart: $dimStart, dimEnd: $dimEnd"

	// log.debug "checking location.mode in settings.smodes"
	if (location.mode in settings.smodes) {
		logDebug("in a sleep mode!")
		brightness = 1
	}
	else if (currentTime < brightenStart.time) {
		// log.debug "currentTime before brightenStart!"
		brightness = minBrightness
	}
	else if (currentTime >= brightenStart.time && currentTime <= brightenEnd.time) {
		// log.debug "currentTime between brightenStart and brightenEnd!"
		brightness = getPercentageValue(brightenStart, brightenEnd, minBrightness, maxBrightness, false)
	}
	else if (currentTime > brightenEnd.time && currentTime < dimStart.time) {
		// log.debug "currentTime between brightenEnd and dimStart!"
		brightness = maxBrightness
	}
	else if (currentTime >= dimStart.time && currentTime <= dimEnd.time) {
		logDebug("currentTime between dimStart and dimEnd!")
		brightness = getPercentageValue(dimStart, dimEnd, minBrightness, maxBrightness, true)
	}
	else if (currentTime > dimEnd.time) {
		// log.debug "currentTime after dimEnd!"
		brightness = minBrightness
	}
	else {
		log.error "currentTime didn't fit into any brightness bucket!"
	}

	return Math.round(brightness)
}
