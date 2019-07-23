/**
 *  Sleep Number Manager
 *
 *  Copyright 2019 Tim Parsons
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
definition(
    name: "Sleep Number Manager",
    namespace: "ClassicTim1",
    author: "Classic_Tim",
    description: "Control your Sleep Number bed vai SmartThings. You can use it to raise, lower, and adjsut the pressure on each side of the bed seperately if it's split, or all together if it's not. If you want to do each side seperately you must use two devices for each side",
    category: "Convenience",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png"
)


preferences {
  page name: "rootPage"
  page name: "findDevicePage"
  page name: "selectDevicePage"
  page name: "createDevicePage"
}

def rootPage() {
  log.trace "rootPage()"
  
  def devices = getChildDevices()
  
  dynamicPage(name: "rootPage", install: true, uninstall: true) {
    section("Settings") {
      input("login", "text", title: "Username", description: "Your SleepIQ username")
      input("password", "password", title: "Password", description: "Your SleepIQ password")
    }
    section("Devices") {
      if (devices.size() > 0) {
        devices.each { device ->
          paragraph title: device.label, "${device.currentBedId} / ${device.currentSide}"
        }
      }
      href "findDevicePage", title: "Create New Device", description: null
    }
  }
}

def findDevicePage() {
  log.trace "findDevicePage()"

  def responseData = getBedData()
  log.debug "Response Data: $responseData"
  
  dynamicPage(name: "findDevicePage") {
    if (responseData.beds.size() > 0) {
      responseData.beds.each { bed ->
        section("Bed: ${bed.bedId}") {
          href "selectDevicePage", title: "Right Side", description: "Right side of the bed", params: [bedId: bed.bedId, side: "Right"]
          href "selectDevicePage", title: "Left Side", description: "Left side of the bed", params: [bedId: bed.bedId, side: "Left"]
        }
      }
    } else {
      section {
        paragraph "No Beds Found"
      }
    }
  }
}

def selectDevicePage(params) {
  log.trace "selectDevicePage()"
  
  settings.newDeviceName = null
  
  dynamicPage(name: "selectDevicePage") {
    section {
      paragraph "Bed ID: ${params.bedId}"
      paragraph "Side: ${params.side}"
      input "newDeviceName", "text", title: "Device Name", description: "Name this device", defaultValue: ""
    }
    section {
      href "createDevicePage", title: "Create Device", description: null, params: [bedId: params.bedId, side: params.side]
    }
  }
}

def createDevicePage(params) {
  log.trace "createDevicePage()"

  def deviceId = "sleepiq.${params.bedId}.${params.side}"
  def device = addChildDevice("sleepNumber", "Sleep Number", deviceId, null, [label: settings.newDeviceName])
  device.setBedId(params.bedId)
  device.setSide(params.side)
  settings.newDeviceName = null
  
  dynamicPage(name: "selectDevicePage") {
    section {
      paragraph "Name: ${device.name}"
      paragraph "Label: ${device.label}"
      paragraph "Bed ID: ${device.curentBedId}"
      paragraph ": ${device.current}"
      paragraph "Presence: ${device.currentPresnce}"
    }
    section {
      href "rootPage", title: "Back to Device List", description: null
    }
  }
}


def installed() {
  log.trace "installed()"
  initialize()
}

def updated() {
  log.trace "updated()"
  unsubscribe()
  unschedule()
  initialize()
}

def initialize() {
  log.trace "initialize()"
  getBedData()
}

def getBedData() {
  log.trace "getBedData()"
    
  state.requestData = null
  updateFamilyStatus()
  while(state.requestData == null) { sleep(100) }
  def requestData = state.requestData
  state.requestData = null
  processBedData(requestData)
  return requestData
}

def processBedData(responseData) {
  if (!responseData || responseData.size() == 0) {
    return
  }
  for(def device : getChildDevices()) {
    for(def bed : responseData.beds) {
      if (device.currentBedId == bed.bedId) {
      def bedSide = bed.leftSide
      	if(device.currentSide == "Right")
        	bedSide = bed.rightSide
        def foundationStatus = updateFoundationStatus(device.currentBedId, device.currentSide)
        String onOff = "off"
        if((device.currentSide == "Right" && foundationStatus.fsCurrentPositionPresetRight != "Flat") || (device.currentSide == "Left" && foundationStatus.fsCurrentPositionPresetLeft != "Flat")){
       		onOff = "on"
        }
        device.updateData(onOff, bedSide.sleepNumber, bedSide.isInBed)
        break;
      }
    }
  }
}





private def ApiHost() { "prod-api.sleepiq.sleepnumber.com" }

private def ApiUriBase() { "https://prod-api.sleepiq.sleepnumber.com" }

private def ApiUserAgent() { "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/28.0.1500.95 Safari/537.36" }

private def updateFamilyStatus(alreadyLoggedIn = false) {
  log.trace "[SleepNumberManager] Updating Family Status"

  if (!state.session || !state.session?.key) {
      login()
  }

  try {
    def statusParams = [
      uri: ApiUriBase() + '/rest/bed/familyStatus?_k=' + state.session?.key,
      headers: [
        'Content-Type': 'application/json;charset=UTF-8',
        'Host': ApiHost(),
        'User-Agent': ApiUserAgent(),
        'Cookie': state.session?.cookies,
        'DNT': '1',
      ],
    ]
    httpGet(statusParams) { response -> 
      if (response.status == 200) {
        state.requestData = response.data
      } else {
          log.error "[SleepNumberManager] Error updating family status - Request was unsuccessful: ($response.status) $response.data"
        state.session = null
        state.requestData = [:]
      }
    }
  } catch(Exception e) {
      log.error "[SleepNumberManager] Error updating family status -  Error ($e)"
      login()
  }
}

private def updateFoundationStatus(String bedId, String currentSide) {
  log.trace "[SleepNumberManager] Updating Foundation Status for: " + currentSide

  if (!state.session || !state.session?.key) {
      login()
  }

  try {
    def statusParams = [
      uri: ApiUriBase() + '/rest/bed/'+bedId+'/foundation/status?_k=' + state.session?.key,
      headers: [
        'Content-Type': 'application/json;charset=UTF-8',
        'Host': ApiHost(),
        'User-Agent': ApiUserAgent(),
        'Cookie': state.session?.cookies,
        'DNT': '1',
      ],
    ]
    httpGet(statusParams) { response -> 
      if (response.status == 200) {
        return response.data
      } else {
        log.error "[SleepNumberManager] Error updating foundation status - Request was unsuccessful: ($response.status) $response.data"
      }
    }
  } catch(Exception e) {
      log.error "[SleepNumberManager] Error updating foundation status - Error ($e)"
      login()
  }
}

private def login() {
  log.trace "[SleepNumberManager] Logging in..."
  state.session = null
  state.requestData = [:]
  try {
    def loginParams = [
      uri: ApiUriBase() + '/rest/login',
      headers: [
        'Content-Type': 'application/json;charset=UTF-8',
        'Host': ApiHost(),
        'User-Agent': ApiUserAgent(),
        'DNT': '1',
      ],
      body: '{"login":"' + settings.login + '","password":"' + settings.password + '"}='
    ]
    httpPut(loginParams) { response ->
      if (response.status == 200) {
        log.trace "[SleepNumberManager] Login was successful"
        state.session = [:]
        state.session.key = response.data.key
        state.session.cookies = ''
        response.getHeaders('Set-Cookie').each {
          state.session.cookies = state.session.cookies + it.value.split(';')[0] + ';'
        }
  		getBedData()
      } else {
        log.trace "[SleepNumberManager] Login failed: ($response.status) $response.data"
        state.session = null
        state.requestData = [:]
      }
    }
  } catch(Exception e) {
    log.error "[SleepNumberManager] Login failed: Error ($e)"
    state.session = null
    state.requestData = [:]
  }
}

private def raiseBed(String bedId, String side){
  log.trace "[SleepNumberManager] Raising bed side "+side+"..."
  put('/rest/bed/'+bedId+'/foundation/preset?_k=', "{'preset':1,'side':"+side+",'speed':0}")
}

private def lowerBed(String bedId, String side){
  log.trace "[SleepNumberManager] Lowering bed side "+side+"..."
  put('/rest/bed/'+bedId+'/foundation/preset?_k=', "{'preset':4,'side':"+side+",'speed':0}")
}

private def setNumber(String bedId, String side, number){
  log.trace "[SleepNumberManager] Setting sleep number side "+side+" to "+number+"..."
  put('/rest/bed/'+bedId+'/sleepNumber?_k=', "{'bed': "+bedId+", 'side': "+side+", 'sleepNumber': "+number+"}")
}

private def put(String uri, String body){
  if (!state.session || !state.session?.key) {
      login()
  }
  uri = uri + state.session?.key
  
  try {
    def statusParams = [
      uri: ApiUriBase() + uri,
      headers: [
        'Content-Type': 'application/json;charset=UTF-8',
        'Host': ApiHost(),
        'User-Agent': ApiUserAgent(),
        'Cookie': state.session?.cookies,
        'DNT': '1',
      ],
      body: body,
    ]
    httpPut(statusParams) { response -> 
      if (response.status == 200) {
  		getBedData()
        return true
      } else {
        log.error "[SleepNumberManager] Put Request failed: "+uri+" : "+body+" : ($response.status) $response.data"
        state.session = null
        state.requestData = [:]
        return false
      }
    }
  } catch(Exception e) {
      log.error "[SleepNumberManager] Put Request failed: "+uri+" : "+body+" : Error ($e)"
      login()
  }
}