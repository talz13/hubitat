/**
*	Hubitat Circadian Daylight 0.72
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
*	0.72 (Apr 01 2019)
*		- Added fix for sunset offset issues
*		- Added zip code override
*
*	0.71 (Mar 29 2019) 
*		- Added fix for modes and switches not overriding
*
*	0.70 (Mar 28 2019) 
*		- Initial (official) release
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
    section("Thank you for installing Circadian Daylight! This application dims and adjusts the color temperature of your lights to match the state of the sun, which has been proven to aid in cognitive functions and restfulness. The default options are well suited for most users, but feel free to tweak accordingly!") {
    }
    section("Control these bulbs; Select each bulb only once") {
        input "ctbulbs", "capability.colorTemperature", title: "Which Temperature Changing Bulbs?", multiple:true, required: false
        input "dimmers", "capability.switchLevel", title: "Which Dimmers?", multiple:true, required: false
    }
    section("What are your 'Sleep' modes? The modes you pick here will dim your lights and filter light to a softer, yellower hue to help you fall asleep easier. Protip: You can pick 'Nap' modes as well!") {
        input "smodes", "mode", title: "What are your Sleep modes?", multiple:true, required: false
    }
    section("Override Constant Brightness (default) with Dynamic Brightness? If you'd like your lights to dim as the sun goes down, override this option. Most people don't like it, but it can look good in some settings.") {
        input "dbright","bool", title: "On or off?", required: false
    }
    section("Disable Circadian Daylight when the following switches are on:") {
        input "dswitches","capability.switch", title: "Switches", multiple:true, required: false
    }

    section("Sunset/sunrise Overrides") {
        input "sunriseOverride", "time", title: "Sunrise Override", required: false
        input "sunsetOverride", "time", title: "Sunset Override", required: false
    }
    section("Color Temperature Overrides"){
        input "coldCTOverride", "number", title: "Cold White Temperature", required: false
        input "warmCTOverride", "number", title: "Warm White Temperature", required: false
    }
    section("Brightness Overrides?") {
        input "maxBrightnessOverride","number", title: "Max Brightness Override", required: false
        input "minBrightnessOverride","number", title: "Min Brightness Override", required: false
    }
    section("Time Before Sunrise / After Sunset to Brighten/Dim?") {
        input "brightenTimeStart", "time", title: "Start Brightening At", required: true
        input "brightenTimeEnd", "time", title: "End Brightening At", required: true
        input "dimTimeStart", "time", title: "Start Dimming At", required: true
        input "dimTimeEnd", "time", title: "End Dimming At", required: true
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

private def initialize() {
    log.debug("initialize() with settings: ${settings}")
    if(ctbulbs) { subscribe(ctbulbs, "switch.on", modeHandler) }
    if(dimmers) { subscribe(dimmers, "switch.on", modeHandler) }
    if(dswitches) { subscribe(dswitches, "switch.off", modeHandler) }
    subscribe(location, "mode", modeHandler)
    
    // revamped for sunset handling instead of motion events
    subscribe(location, "sunset", modeHandler)
    subscribe(location, "sunrise", modeHandler)
    // schedule("0 */15 * * * ?", modeHandler)
    schedule("0 */5 * * * ?", modeHandler)
    subscribe(app,modeHandler)
    subscribe(location, "sunsetTime", scheduleTurnOn)
    // rather than schedule a cron entry, fire a status update a little bit in the future recursively
    scheduleTurnOn()
}

private def getSunriseTime(){
	def sunRiseSet 
	def sunriseTime
	
	sunRiseSet = getSunriseAndSunset()
	if(settings.sunriseOverride != null && settings.sunriseOverride != ""){
		 sunriseTime = new Date().parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", settings.sunriseOverride)
	}
	else if(settings.sunriseOffset != null && settings.sunriseOffset != ""){
		sunriseTime = sunRiseSet.sunrise.plusMinutes(settings.sunriseOffset)
	}
	else{
	    sunriseTime = sunRiseSet.sunrise
	}
	return sunriseTime
}

private def getSunsetTime(){
	def sunRiseSet 
	def sunsetTime
	
	sunRiseSet = getSunriseAndSunset()
	if(settings.sunsetOverride != null && settings.sunsetOverride != ""){
		sunsetTime = new Date().parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", settings.sunsetOverride)
	}
	else{
	    sunsetTime = sunRiseSet.sunset
	}
	return sunsetTime
}

def scheduleTurnOn() {
    def int iterRate = 20
    
    // get sunrise and sunset times
    def sunriseTime = getSunriseTime()
    log.debug("sunrise time ${sunriseTime}")
	
    def sunsetTime = getSunsetTime()
    log.debug("sunset time ${sunsetTime}")
    
    if(sunriseTime > sunsetTime) {
        sunriseTime = new Date(sunriseTime - (24 * 60 * 60 * 1000))
    }
    
    def runTime = new Date(now() + 60*15*1000)
    for (def i = 0; i < iterRate; i++) {
        def long uts = sunriseTime.time + (i * ((sunsetTime.time - sunriseTime.time) / iterRate))
        def timeBeforeSunset = new Date(uts)
        if(timeBeforeSunset.time > now()) {
            runTime = timeBeforeSunset
            last
        }
    }
    
	log.debug "checking... ${runTime.time} : $runTime. state.nextTime is ${state.nextTime}"
    if(state.nextTime != runTime.time) {
        state.nextTime = runTime.time
        log.debug "Scheduling next step at: $runTime (sunset is $sunsetTime) :: ${state.nextTime}"
        runOnce(runTime, modeHandler)
    }
}


// Poll all bulbs, and modify the ones that differ from the expected state
def modeHandler(evt) {
    log.debug "modeHandler called"
    for (dswitch in dswitches) {
        if(dswitch.currentSwitch == "on") {
            return
        }
    }
    
    log.debug "modeHandler getGraduatedBrightness()"
    def ctb = getGraduatedBrightness()

    def ct = ctb.colorTemp
    def bright = ctb.brightness
    
    for(ctbulb in ctbulbs) {
        log.debug "modeHandler ctbulb in ctbulbs"
        if(ctbulb.currentValue("switch") == "on") {
            if((settings.dbright == true || location.mode in settings.smodes) && ctbulb.currentValue("level") != bright) {
                ctbulb.setLevel(bright)
            }
            if(ctbulb.currentValue("colorTemperature") != ct) {
                ctbulb.setColorTemperature(ct)
            }
        }
    }
    // def color = [hex: hex, hue: hsv.h, saturation: hsv.s, level: bright]
    for(dimmer in dimmers) {
        log.debug "modeHandler dimmer in dimmers"
        if(dimmer.currentValue("switch") == "on") {
        	if(dimmer.currentValue("level") != bright) {
            	dimmer.setLevel(bright)
            }
        }
    }
    
    // log.debug "modeHandler scheduleTurnOn"
    scheduleTurnOn()
}

def getGraduatedBrightness() {
    // log.debug "Calling getGraduatedBrightness"
    def brightenStart = settings.brightenTimeStart == null || settings.brightenTimeStart == "" ? getSunriseTime() : Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", settings.brightenTimeStart)
    def brightenEnd = settings.brightenTimeEnd == null || settings.brightenTimeEnd == "" ? getSunriseTime() : Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", settings.brightenTimeEnd)
    def dimStart = settings.dimTimeStart == null || settings.dimTimeStart == "" ? getSunsetTime() : Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", settings.dimTimeStart)
    def dimEnd = settings.dimTimeEnd == null || settings.dimTimeEnd == "" ? getSunsetTime() : Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", settings.dimTimeEnd)

    def int maxBrightness = settings.maxBrightnessOverride == null || settings.maxBrightnessOverride == "" ? 100 : settings.maxBrightnessOverride
    def int minBrightness = settings.minBrightnessOverride == null || settings.minBrightnessOverride == "" ? 1 : settings.minBrightnessOverride
    def int brightnessRange = maxBrightness - minBrightness

    def int colorTemp = settings.coldCTOverride == null || settings.coldCTOverride == "" ? 2700 : settings.coldCTOverride
	def int coldCT = settings.coldCTOverride == null || settings.coldCTOverride == "" ? 6500 : settings.coldCTOverride
	def int warmCT = settings.warmCTOverride == null || settings.warmCTOverride == "" ? 2700 : settings.warmCTOverride
    def int ctRange = coldCT - warmCT

    def brightness = 100

    def currentTime = now()
    // log.debug "currentTime: $currentTime, brightenStart: $brightenStart, brightenEnd: $brightenEnd, dimStart: $dimStart, dimEnd: $dimEnd"

    log.debug "checking location.mode in settings.smodes"
    if (location.mode in settings.smodes) {
        log.debug "in a sleep mode!"
        brightness = 1
        colorTemp = warmCT
    }

    if (currentTime < brightenStart.time) {
        log.debug "currentTime before brightenStart!"
        brightness = minBrightness
        colorTemp = warmCT
    }
    else if (currentTime >= brightenStart.time && currentTime <= brightenEnd.time) {
        log.debug "currentTime between brightenStart and brightenEnd!"
        percentThrough = ((currentTime - brightenStart.time) / (brightenEnd.time - brightenStart.time))
        brightness = (percentThrough * brightnessRange) + minBrightness
        colorTemp = (percentThrough * ctRange) + warmCT
    }
    else if (currentTime > brightenEnd.time && currentTime < dimStart.time) {
        log.debug "currentTime between brightenEnd and dimStart!"
        brightness = maxBrightness
        colorTemp = coldCT
    }
    else if (currentTime >= dimStart.time && currentTime <= dimEnd.time) {
        log.debug "currentTime between dimStart and dimEnd!"
        percentRemaining = 1 - ((currentTime - dimStart.time) / (dimEnd.time - dimStart.time))
        brightness = (percentRemaining * brightnessRange) + minBrightness
        colorTemp = (percentRemaining * ctRange) + warmCT
    }
    else if (currentTime > dimEnd.time) {
        log.debug "currentTime after dimEnd!"
        brightness = minBrightness
        colorTemp = warmCT
    }
    else {
        log.error "currentTime didn't fit into any bucket!"
    }

    // log.debug "setting ctb variable!"
    def ctb = [:]
    ctb = [colorTemp: colorTemp, brightness: Math.round(brightness)]
    log.debug "ctb: $ctb"
    // log.debug "Ending getGraduatedBrightness!"
    return ctb
}
