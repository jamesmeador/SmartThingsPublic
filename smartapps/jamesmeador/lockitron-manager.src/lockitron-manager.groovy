/**
 *  Lockitron Manager
 *
 *  Copyright 2017 James Meador
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
include 'asynchttp_v1'

definition(
    name: "Lockitron Manager",
    namespace: "jamesmeador",
    author: "James Meador",
    description: "Connect to Lockitron Bolt devices (Lockitron Bridge required)",
    category: "Safety & Security",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    singleInstance: true) {
    appSetting "oauthClientId"
    appSetting "oauthSecret"
}

mappings {
   	path("/oauth/initialize") {action: [GET: "oauthInitUrl"]}
    path("/oauth/callback") {action: [GET: "callback"]}
    path("/lockitronhook") {action: [POST: "webhook"]}
}

preferences {
	page(name: "auth", title: "Lockitron", nextPage:"", content:"authPage", uninstall: true, install:true)
	page(name: "main", title: "Lockitron", nextPage:"", content:"mainPage", uninstall: true, install:false)
    page(name: "deviceSelectPage", title: "Lockitron", nextPage:"", content:"deviceSelectPage", uninstall: false, install:false)
    
}

def webhook() {
	processChildState(request.JSON.data.lock.id, request.JSON.data.lock.status)

}

def getAccessToken() {
	try {
		if(!state?.accessToken) { createAccessToken() }
		else { return true }
	}
	catch (ex) {
		def msg = "Error: OAuth is not Enabled for the Lockitron Manager application!.  Please click remove and Enable Oauth under the SmartApp App Settings in the IDE"
		LogAction("getAccessToken Exception | $msg", "warn", true)
		return false
	}
}

def authPage() {
	getAccessToken()
    log.debug('start authPage')
    def redirectUrl = "https://graph.api.smartthings.com/oauth/initialize?appId=${app.id}&access_token=${state.accessToken}&apiServerUrl=${getApiServerUrl()}"
    if(!state.authToken) {
        return dynamicPage(name: "auth", title: "Login", nextPage: "", uninstall: false) {
            section() {
                paragraph "tap below to log in to Lockitron and authorize SmartThings access"
                href(url: redirectUrl, style: "embedded", required: true, title: "Lockitron Account", description: "Click to enter credentials")
            }
        }
    } else {
    	return mainPage()
    }
}

def mainPage() {
	return dynamicPage(name: "main", title: "Settings", uninstall: true, install: true) {
    	section() {
        	def desc = "Tap to configure the Lockitron locks that SmartThings has access to control"
            href("deviceSelectPage", title: "Locks", description: desc)
        }
    }
}

def listBoltDevices() {
	def result = false
	def deviceListParams = [
    	uri: "https://api.lockitron.com/v2/locks",
        requestContentType: "application/json",
        query: [access_token: state.authToken]
	]
    LogTrace(deviceListParams)
    httpGet(deviceListParams) { resp ->
		def bolt_devices = resp.data.collectEntries { [(it.id): it] }
        state.boltDevices = bolt_devices
        return bolt_devices
    }
}

def deviceSelectPage() {
	def bolt_devices = listBoltDevices()
    def inputOpts = bolt_devices.collect { [(it.key):it.value.name] }
	return dynamicPage(name: "deviceSelectPage", title: "Locks", uninstall: false, install: true) {
    	section() {
        	paragraph "Tap to add your locks!"
        	input(name: "locks", title:"", type: "enum", required:true, multiple:true, description: "Tap to choose", options: inputOpts )
        }
    
    }
}

def installed() {
	log.debug "Installed with settings: ${settings}"

	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"

	unsubscribe()
	initialize()
}

def lock(child) {
	log.debug("lock in smartapp")
    lockStateChange(child.device.deviceNetworkId, "lock")
}

def unlock(child) {
	log.debug("unlock in smartapp")
    lockStateChange(child.device.deviceNetworkId, "unlock")
}

def refresh(child) {
	log.debug("refresh in smartapp")
    pollDevice(child.device.deviceNetworkId)
}


def lockStateChange(lockId, lockState) {
	log.debug("accesstoken: ${state.accessToken}")
    log.debug("app.id: ${app.id}")
	def queryParams = [
    	uri: "https://api.lockitron.com/v2/locks/",
        path: lockId,
        requestContentType: "application/json",
        query: [access_token: state.authToken, state: lockState]
	]	
    LogTrace(queryParams)
    asynchttp_v1.put("processLockStateChangeResponse", queryParams)
}

def processLockStateChangeResponse(response, _data) {
	log.debug(response.data)
    def json = response.getJson()
    processChildState(json.id, json.state)
}

def processChildState(lockId, state) {
	def child = getChildDevice(lockId)
    child.processState(state)
}

def initialize(){
	if (!state.devices) {
		state.devices = [:]    	
    }
    settings.locks.each {deviceId ->
        try {
            def existingDevice = getChildDevice(deviceId)
            if(!existingDevice) {
            	def bolt_device = state.boltDevices[deviceId]
                def childDevice = addChildDevice(app.namespace, "Lockitron Bolt", deviceId, null, [name: deviceId, label: bolt_device.name])
            }
            pollDevice(deviceId)

        } catch (e) {
            log.error "Error creating device: ${e}"
        }
    }
    def delete = getChildDevices().findAll { !settings.locks.contains(it.deviceNetworkId) }

    delete.each {
        deleteChildDevice(it.deviceNetworkId)
    }
    runEvery5Minutes(pollChildren)
}

def pollChildren() {
	def children = getChildDevices()
    children.each {
    	pollDevice(it.deviceNetworkId)
    }
}


def pollDevice(lockId) {
	log.debug('polling')
    log.info "Set up web page located at : ${getApiServerUrl()}/api/smartapps/installations/${app.id}/cheat?access_token=${state.accessToken}"
    def pollParams = [
        uri: "https://api.lockitron.com/v2/locks/",
        path: lockId,
        requestContentType: "application/json",
        query: [access_token: state.authToken]
	]    
    httpGet(pollParams) { resp ->
    	def child = getChildDevice(lockId)
        log.debug(resp.data)
        child.processState(resp.data.state)
    }
}


def oauthInitUrl() {
	log.error("got here")

    // Generate a random ID to use as a our state value. This value will be used to verify the response we get back from the 3rd party service.
    state.oauthInitState = UUID.randomUUID().toString()
	def apiEndpoint = "https://api.lockitron.com/oauth"
    def oauthParams = [
        response_type: "code",
        client_id: appSettings.oauthClientId,
        state: state.oauthInitState,
        redirect_uri: "https://graph.api.smartthings.com/oauth/callback"
    ]
    def apiQueryString = "${apiEndpoint}/authorize?${toQueryString(oauthParams)}"
	log.debug(apiQueryString)
    redirect(location: apiQueryString)
}

// The toQueryString implementation simply gathers everything in the passed in map and converts them to a string joined with the "&" character.
String toQueryString(Map m) {
        return m.collect { k, v -> "${k}=${URLEncoder.encode(v.toString())}" }.sort().join("&")
}

def callback() {
	try {
		LogTrace("callback()>> params: $params, params.code ${params.code}")
		def code = params.code
		LogTrace("Callback Code: ${code}")
		def oauthState = params.state
		LogTrace("Callback State: ${oauthState}")

		if(oauthState == state.oauthInitState){
			def tokenParams = [
				code: code.toString(),
				client_id: appSettings.oauthClientId,
				client_secret: appSettings.oauthSecret,
				grant_type: "authorization_code",
                redirect_uri: "https://graph.api.smartthings.com/oauth/callback"
			]
			def tokenUrl = "https://api.lockitron.com/oauth/token?${toQueryString(tokenParams)}"
            LogTrace("Token URL: ${tokenUrl}")
			httpPost(uri: tokenUrl) { resp ->
				state.tokenExpires = resp?.data.expires_in
				state.authToken = resp?.data.access_token
			}

			if(state?.authToken) {
				LogAction("Lockitron AuthToken Generated SUCCESSFULLY", "info", true)
				//generateInstallId
				success()
			} else {
				LogAction("Failure Generating Lockitron AuthToken", "error", true)
				fail()
			}
		}
		else { LogAction("callback() oauthState != state.oauthInitState", "error", true) }
	}
	catch (ex) {
		log.error "Callback Exception:", ex
	}
}

def success() {
        def message = """
                <p>Your account is now connected to SmartThings!</p>
                <p>Click 'Done' to finish setup.</p>
        """
        displayMessageAsHtml(message)
}

// Example fail method
def fail() {
    def message = """
        <p>There was an error connecting your account with SmartThings</p>
        <p>Please try again.</p>
    """
    displayMessageAsHtml(message)
}

def displayMessageAsHtml(message) {
    def html = """
        <!DOCTYPE html>
        <html>
            <head>
            </head>
            <body>
                <div>
                    ${message}
                </div>
            </body>
        </html>
    """
    render contentType: 'text/html', data: html
}

/************************************************************************************************
|									LOGGING AND Diagnostic										|
*************************************************************************************************/
def LogTrace(msg, logSrc=null) {
	def trOn = true
	if(trOn) {
		def theLogSrc = (logSrc == null) ? (parent ? "Automation" : "LockitronManager") : logSrc
		Logger(msg, "trace", theLogSrc)
	}
}

def LogAction(msg, type="debug", showAlways=false, logSrc=null) {
	def isDbg = parent ? ((state.showDebug || showDebug)  ? true : false) : (appDebug ? true : false)
	def theLogSrc = (logSrc == null) ? (parent ? "Automation" : "LockitronManager") : logSrc
	if(showAlways) { Logger(msg, type, theLogSrc) }
	else if(isDbg && !showAlways) { Logger(msg, type, theLogSrc) }
}

def Logger(msg, type, logSrc=null) {
	if(msg && type) {
		def labelstr = ""
		if(state?.debugAppendAppName == null) {
			def tval = parent ? parent?.settings?.debugAppendAppName : settings?.debugAppendAppName
			state?.debugAppendAppName = (tval || tval == null) ? true : false
		}
		if(state?.debugAppendAppName) { labelstr = "${app.label} | " }
		def themsg = "${labelstr}${msg}"
		switch(type) {
			case "debug":
				log.debug "${themsg}"
				break
			case "info":
				log.info "||| ${themsg}"
				break
			case "trace":
				log.trace "||${themsg}"
				break
			case "error":
				log.error "|${themsg}"
				break
			case "warn":
				log.warn "||${themsg}"
				break
			default:
				log.debug "${themsg}"
				break
		}
		//log.debug "Logger remDiagTest: $msg | $type | $logSrc"
	    if(!parent) { /*saveLogtoRemDiagStore(themsg, type, logSrc)*/ }
		else {
			if(state?.enRemDiagLogging == null) {
				state?.enRemDiagLogging = parent?.state?.enRemDiagLogging
				if(state?.enRemDiagLogging == null) {
					state?.enRemDiagLogging = false
				}
				//log.debug "set enRemDiagLogging to ${atomicState?.enRemDiagLogging}"
			}
			if(state?.enRemDiagLogging) {
				//parent.saveLogtoRemDiagStore(themsg, type, logSrc)
			}
		}
	}
	else { log.error "${labelstr}Logger Error - type: ${type} | msg: ${msg} | logSrc: ${logSrc}" }
}

