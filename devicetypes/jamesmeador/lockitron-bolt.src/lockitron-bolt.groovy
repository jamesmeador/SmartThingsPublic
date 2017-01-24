/**
 *  Lockitron Bolt
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
metadata {
	definition (name: "Lockitron Bolt", namespace: "jamesmeador", author: "James Meador") {
		capability "Battery"
		capability "Lock"
        capability "Polling"
        capability "Refresh"
	}


	simulator {
		// TODO: define status and reply messages here
	}

	tiles {
		standardTile("lockTile", "device.lock", width:2, height: 2, canChangeIcon: true) {
        	state "locked", label: "LOCKED", action: "unlock", nextState: "unlocking", icon: "st.locks.lock.locked", backgroundColor: "#ff0000"
            state "unlocked", label: "UNLOCKED", action: "lock", nextState: "locking", icon: "st.locks.lock.unlocked", backgroundColor: "#00ff00"
            state "locking", label: "Locking...", icon: "st.locks.lock.locked"
            state "unlocking", label: "Unlocking...", icon: "st.locks.lock.unlocked"
        }
		standardTile("refresh", "device.refresh", width:1, height: 1) {
			state "default", label:'', action:"refresh.refresh", icon:"st.secondary.refresh"
		}
        
        main("lockTile")
        details("lockTile", "refresh")
	}
}

// parse events into attributes
def parse(String description) {
	log.debug("parse!")
    
}

// handle commands
def lock() {
	log.debug "Executing 'lock'"
	parent.lock(this)
}

def unlock() {
	log.debug "Executing 'unlock'"
	parent.unlock(this)
}

def refresh() {
	parent.refresh(this)
}

def processState(lockState) {
	if (lockState == "lock") { setStateToLocked() }
    if (lockState == "unlock") { setStateToUnlocked() }
}

def setStateToLocked() {
	setState("locked")
}

def setStateToUnlocked() {
	setState("unlocked")
}

// Set state from cloud
def setState(lockState) {
	log.debug('setting state to:')
    log.debug(lockState)
	sendEvent(name: "lock", value: lockState)
}

def poll() {
	parent.pollChildren()
}

