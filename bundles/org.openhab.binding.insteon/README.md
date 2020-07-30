# Insteon Binding

Insteon is a home area networking technology developed primarily for connecting light switches and loads.
Insteon devices send messages either via the power line, or by means of radio frequency (RF) waves, or both (dual-band.
A considerable number of Insteon compatible devices such as switchable relays, thermostats, sensors etc are available.
More about Insteon can be found on [Wikipedia](http://en.wikipedia.org/wiki/Insteon).

This binding provides access to the Insteon network by means of either an Insteon PowerLinc Modem (PLM), a legacy Insteon Hub 2242-222 or the current 2245-222 Insteon Hub.
The modem can be connected to the openHAB server either via a serial port (Model 2413S) or a USB port (Model 2413U.
The Insteon PowerLinc Controller (Model 2414U) is not supported since it is a PLC not a PLM.
The modem can also be connected via TCP (such as ser2net.
The binding translates openHAB commands into Insteon messages and sends them on the Insteon network.
Relevant messages from the Insteon network (like notifications about switches being toggled) are picked up by the modem and converted to openHAB status updates by the binding.
The binding also supports sending and receiving of legacy X10 messages.

The binding does not support linking new devices on the fly, i.e. all devices must be linked with the modem *before* starting the Insteon binding.

The openHAB binding supports minimal configuration of devices, currently only monitoring and sending messages.
For all other configuration and set up of devices, link the devices manually via the set buttons, or use the free [Insteon Terminal](https://github.com/pfrommerd/insteon-terminal) software.
The free HouseLinc software from Insteon can also be used for configuration, but it wipes the modem link database clean on its initial use, requiring to re-link the modem to all devices.

## Supported Things

| Thing | Type | Description |
|-------|------|-------------|
| network  | Bridge | An Insteon PLM or hub that is used to communicate with the Insteon devices |
| device | Thing | Insteon devices such as dimmers, keypads, sensors, etc. |
| scene | Thing | Insteon scenes |
| x10 | Thing | X10 devices such as a switch, dimmer or sensor |

## Discovery

The network bridge is not automatically discovered, you will have to manually add the it yourself.
Upon proper configuration of the network bridge, the network device database will be downloaded.
Depending on the bridge discovery parameters, any Insteon devices or scenes that exists in the database and is not currently configured may automatically be added to the inbox.
The naming convention for devices is **Insteon _Product Description_** if the product data was retrieved, otherwise **Insteon Device AABBCC**, where AA, BB and CC are from the Insteon device address.
For scenes, it is **Insteon Scene 42**, where 42 is the broadcast group number.
The device auto-discovery is enabled by default while disabled for scenes.
X10 devices are not auto discovered.

## Thing Configuration

### Network Configuration

The Insteon PLM or hub is configured with the following parameters:

| Parameter | Default | Required | Description |
|-----------|:-------:|:--------:|-------------|
| port | | Yes | **Examples:**<br>- PLM on  Linux: `/dev/ttyS0` or `/dev/ttyUSB0`<br>- Smartenit ZBPLM on Linux: `/dev/ttyUSB0,baudRate=115200`<br>- PLM on Windows: `COM1`<br>- Current  hub (2245-222) at 192.168.1.100 on port 25105, with a poll interval of 1000 ms (1 second): `/hub2/my_user_name:my_password@192.168.1.100:25105,poll_time=1000`<br>- Legacy hub (2242-222) at 192.168.1.100 on port 9761:`/hub/192.168.1.100:9761`<br>- Networked PLM using ser2net at 192.168.1.100 on port 9761:`/tcp/192.168.1.100:9761` |
| devicePollIntervalSeconds | 300 | Yes | Poll interval of devices in seconds. Poll too often and you will overload the Insteon network, leading to sluggish or no response when trying to send messages to devices. The default poll interval of 300 seconds has been tested and found to be a good compromise in a configuration of about 110 switches/dimmers. |
| deviceDiscoveryEnabled | true | Yes | Enable discovery of Insteon devices found in the modem database but not configured. |
| sceneDiscoveryEnabled | false | Yes | Enable discovery of Insteon scenes found in the modem database but not configured. |
| additionalDeviceTypes | | No | Additional device types file path. The syntax of the file is identical to the `device_types.xml` file in the source tree. |
| additionalFeatures | | No | Additional feature templates file path. The syntax of the file is identical to the `device_features.xml` file in the source tree. |
| additionalProducts | | No | Additional products file path. The syntax of the file is identical to the `device_products.xml` file in the source tree. |

>NOTE: For users upgrading from InsteonPLM, The parameter port_1 is now port.

### Insteon Device Configuration

The Insteon device is configured with the following parameters:

| Parameter | Required | Description |
|-----------|:--------:|-------------|
| address | Yes | Insteon address of the device. It can be found on the device. Example: 12.34.56. |
| devCat | No | Insteon device category. It is used to identify the device type in conjunction with subCat parameter. It can be left blank to have the binding automatically retrieve that information from the device directly. For battery powered devices, it will take until the device is awake to get it. It will take precedence over the device value if configured. Example: 0x01 |
| subCat | No | Insteon device sub category. It is used to identify the device type in conjunction with devCat parameter. It can be left blank to have the binding automatically retrieve that information from the device directly. For battery powered devices, it will take until the device is awake to get it. It will take precedence over the device value if configured. Example: 0x01 |
| productKey | No | Insteon product key. It is used to identify the device type. It can be left blank to have the binding automatically retrieve that information from the device directly. For battery powered devices, it will take until the device is awake to get it. It will take precedence over the device value but not over the devCat/subCat parameters if configured. Example: 0x01 |

The Insteon device address is sufficient for the binding to determine the device type.
However, to speed up the initialization sequence, especially for battery powered devices, the devCat and subCat parameters can be specified.
The Insteon product key can also be used to identify the device over the categories parameters if necessary.
It is important to note that these product key are mostly different from the one used in the previous version of this binding.

A list of the supported products can be found [here](https://raw.githubusercontent.com/openhab/openhab-addons/master/bundles/org.openhab.binding.insteon/src/main/resources/device_products.xml).

### Insteon Scene Configuration

The Insteon scene is configured with the following parameter:

| Parameter | Required | Description |
|-----------|:--------:|-------------|
| group | Yes | Insteon scene group number between 1 and 254 and can be found in the scene detailed information in the Insteon mobile app. |

### X10 Devices Configuration

The X10 device is configured with the following parameters:

| Parameter | Required | Description |
|-----------|:--------:|-------------|
| houseCode | Yes | X10 house code of the device. Example: A|
| unitCode | Yes | X10 unit code of the device. Example: 1 |
| deviceType | Yes | X10 device type |

The following is a list of the supported X10 device types:

| Device Type | Description |
|-------------|-------------|
| X10_Switch | X10 Switch |
| X10_Dimmer | X10 Dimmer |
| X10_Sensor | X10 Sensor |

## Channels

Below is the list of possible channels for the Insteon devices.
In order to determine which channels a device supports, you can look at the device in the UI, or with the commands `display_devices` and `display_scenes` in the console.

### State Channels

| Channel | Type | Access Mode | Description |
|---------|------|-------------|-------------|
| acDelay | Number:Time | R/W | Thermostat AC Delay |
| backlightDuration | Number:Time | R/W | Thermostat Back Light Duration |
| batteryLevel | Number:Dimensionless | R | Battery Level |
| batteryPowered | Switch | R | Battery Powered State |
| beep | Switch | W | Beep (write only) |
| broadcastOnOff | Switch | W | Scene Broadcast On/Off (write only) |
| buttonA | Switch | R/W | Button A |
| buttonB | Switch | R/W | Button B |
| buttonC | Switch | R/W | Button C |
| buttonD | Switch | R/W | Button D |
| buttonE | Switch | R/W | Button E |
| buttonF | Switch | R/W | Button F |
| buttonG | Switch | R/W | Button G |
| buttonH | Switch | R/W | Button H |
| buttonBeep | Switch | R/W | Beep on Button Press |
| buttonConfig | String | R/W | Keypad Button Config |
| buttonLock | Switch | R/W | Thermostat Button Lock |
| carbonMonoxideAlarm | Switch | R | Smoke Sensor Carbon Monoxide Alarm |
| contact | Contact | R | Sensor Contact State |
| coolSetPoint | Number:Temperature | R/W | Thermostat Cool Set Point |
| daylight | Contact | R | Sensor Daylight State |
| dimmer | Dimmer | R/W | Light Dimmer |
| energyOffset | Number:Temperature | R/W | Thermostat Energy Set Point Offset |
| energySaving | Switch | R | Thermostat Energy Saving |
| fanMode | String | R/W | Thermostat Fan Mode |
| fanSpeed | String | R/W | Ceiling Fan Speed |
| fastOnOff | Switch | W | Fast On/Off (write only) |
| heatSetPoint | Number:Temperature | R/W | Thermostat Heat Set Point |
| humidity | Number:Dimensionless | R | Current Humidity |
| humidityHigh | Number:Dimensionless | R/W | Thermostat Humidity High |
| humidityLow | Number:Dimensionless | R/W | Thermostat Humidity Low |
| kWh | Number:Energy | R | Power Meter Kilowatt Hour |
| lastHeardFrom | DateTime | R | Last Heard From |
| ledBrightness | Dimmer | R/W | LED Brightness |
| ledOnOff | Switch | R/W | LED On/Off |
| ledTraffic | Switch | R/W | LED Blink on Traffic |
| lightDimmer | Dimmer | R/W | Ceiling Fan Light Dimmer |
| lightLevel | Number:Dimensionless | R | Sensor Light Level |
| loadSense | Switch | R/W | Load Sense Outlet |
| loadSenseBottom | Switch | R/W | Load Sense Bottom Outlet |
| loadSenseTop | Switch | R/W | Load Sense Top Outlet |
| lock | Switch | R/W | Lock |
| lowBattery | Switch | R | Sensor Low Battery |
| manualChange | String | W | Manual Change (write only) |
| momentaryDuration | Number:Time | R/W | I/O Momentary Duration |
| motionDetected | Switch | R | Motion Sensor Motion Detected |
| onLevel | Dimmer | R/W | On Level |
| outletBottom | Switch | R/W | Outlet Bottom |
| outletTop | Switch | R/W | Outlet Top |
| programLock | Switch | R/W | Local Programming Lock |
| rampDimmer | Dimmer | W | Ramp Dimmer (write only) |
| rampRate | Number:Time | R/W | Ramp Rate |
| relayMode | String | R/W | I/O Linc Output Relay Mode |
| relaySensorFollow | Switch | R/W | I/O Linc Output Relay Follows Input Sensor |
| reset | Switch | W | Power Meter Reset (write only) |
| resumeDimLevel | Switch | R/W | Resume Dim Level |
| rollershutter | Rollershutter | R/W | Rollershutter |
| smokeAlarm | Switch | R | Smoke Sensor Smoke Alarm |
| stage1Duration | Number:Time | R/W | Thermostat Stage 1 Duration |
| stayAwake | Switch | R/W | Sensor Stay Awake for Extended Time (write only on some products) |
| switch | Switch | R/W | Switch |
| syncTime | Switch | W | Thermostat Sync Time (write only) |
| systemMode | String | R/W | System Mode |
| systemState | String | R | System State |
| tamperSwitch | Contact | R | Sensor Tamper Switch |
| temperature | Number:Temperature | R | Current Temperature |
| temperatureFormat | String | R/W | Thermostat Temperature Format |
| testAlarm | Switch | R | Smoke Sensor Test Alarm |
| timeFormat | String | R/W | Thermostat Time Format |
| toggleModeButtonA | Switch | R/W | Toggle Mode Button A |
| toggleModeButtonB | Switch | R/W | Toggle Mode Button B |
| toggleModeButtonC | Switch | R/W | Toggle Mode Button C |
| toggleModeButtonD | Switch | R/W | Toggle Mode Button D |
| toggleModeButtonE | Switch | R/W | Toggle Mode Button E |
| toggleModeButtonF | Switch | R/W | Toggle Mode Button F |
| toggleModeButtonG | Switch | R/W | Toggle Mode Button G |
| toggleModeButtonH | Switch | R/W | Toggle Mode Button H |
| update | Switch | W | Power Meter Update (write only) |
| waterDetected | Switch | R | Leak Sensor Water Detected |
| watts | Number:Power | R | Power Meter Watts |

### Trigger Channels

| Channel | Description |
|---------|-------------|
| eventButton | Event Button |
| eventButtonMain | Event Button Main |
| eventButtonA | Event Button A |
| eventButtonB | Event Button B |
| eventButtonC | Event Button C |
| eventButtonD | Event Button D |
| eventButtonE | Event Button E |
| eventButtonF | Event Button F |
| eventButtonG | Event Button G |
| eventButtonH | Event Button H |

The following is a list of the supported triggered events:

| Event | Description |
|-------|-------------|
| `PRESSED_ON` | Button Pressed On (Regular On) |
| `PRESSED_OFF` | Button Pressed Off (Regular Off) |
| `DOUBLE_PRESSED_ON` | Button Double Pressed On (Fast On) |
| `DOUBLE_PRESSED_OFF` | Button Double Pressed Off (Fast Off) |
| `HELD_UP` | Button Held Up (Manual Change Up) |
| `HELD_DOWN` | Button Held Down (Manual Change Down) |
| `RELEASED` | Button Released (Manual Change Stop) |

## Full Example

Sample things file:

```
Bridge insteon:network:home [port="/dev/ttyUSB0"] {
  Thing device 22F8A8 [address="22.F8.A8", devCat="0x01" subCat="0x42"]
  Thing device 238D93 [address="23.8D.93", devCat="0x02" subCat="0x0D"]
  Thing device 238F55 [address="23.8F.55", devCat="0x01" subCat="0x1F"]
  Thing device 238FC9 [address="23.8F.C9", productKey="0x00006B"]
  Thing device 23B0D9 [address="23.B0.D9"]
  Thing scene scene42 [group=42]
  Thing x10 A2 [houseCode="A", unitCode=2, deviceType="X10_Switch"]
}
```

Sample items file:

```
Switch switch1 { channel="insteon:device:home:243141:switch" }
Dimmer dimmer1 { channel="insteon:device:home:238F55:dimmer" }
Dimmer dimmer2 { channel="insteon:device:home:23B0D9:dimmer" }
Dimmer dimmer3 { channel="insteon:device:home:238FC9:dimmer" }
Dimmer keypad  { channel="insteon:device:home:22F8A8:dimmer" }
Switch keypadA { channel="insteon:device:home:22F8A8:buttonA" }
Switch keypadB { channel="insteon:device:home:22F8A8:buttonB" }
Switch keypadC { channel="insteon:device:home:22F8A8:buttonC" }
Switch keypadD { channel="insteon:device:home:22F8A8:buttonD" }
Switch scene42 { channel="insteon:scene:home:scene42:broadcastOnOff" }
Switch switch2 { channel="insteon:x10:home:A2:switch" }
```

## Console Commands

The binding provides commands you can use to help with troubleshooting.
Enter `smarthome:insteon` in the console and you will get a list of available commands.

```
openhab> smarthome:insteon
Usage: smarthome:insteon display_devices - display Insteon/X10 devices that are configured, along with available channels and status
Usage: smarthome:insteon display_channels - display channel ids that are available, along with configuration information and link state
Usage: smarthome:insteon display_device_database address - display device all-link database records
Usage: smarthome:insteon display_device_product_data address - display device product data
Usage: smarthome:insteon display_modem_database - display Insteon PLM or hub database details
Usage: smarthome:insteon display_monitored - display monitored device(s)
Usage: smarthome:insteon display_scenes - display Insteon scenes that are configured, along with available channels
Usage: smarthome:insteon start_monitoring all|address - start displaying messages received from device(s)
Usage: smarthome:insteon stop_monitoring all|address - stop displaying messages received from device(s)
Usage: smarthome:insteon send_standard_message address flags cmd1 cmd2 - send standard message to a device
Usage: smarthome:insteon send_extended_message address flags cmd1 cmd2 [up to 13 bytes] - send extended message to a device
Usage: smarthome:insteon send_extended_message_2 address flags cmd1 cmd2 [up to 12 bytes] - send extended message with a two byte crc to a device
```

Here is an example of command: `smarthome:insteon display_modem_database`.

When monitoring devices, the output will be displayed where openHAB was started.
You may need to redirect the output to a log file to see the messages.
The send message commands do not display any results.
If you want to see the response from the device, you will need to monitor the device.

## Insteon Groups and Scenes

How do Insteon devices tell other devices on the network that their state has changed? They send out a broadcast message, labeled with a specific *group* number.
All devices (called *responders*) that are configured to listen to this message will then go into a pre-defined state.
For instance when light switch A is switched to "ON", it will send out a message to group #1, and all responders will react to it, e.g they may go into the "ON" position as well.
Since more than one device can participate, the sending out of the broadcast message and the subsequent state change of the responders is referred to as "triggering a scene".

Many Insteon devices send out messages on different group numbers, depending on what happens to them.
A leak sensor may send out a message on group #1 when dry, and on group #2 when wet.
The default group used for e.g. linking two light switches is usually group #1.

## Insteon Binding Process

Before Insteon devices communicate with one another, they must be linked.
During the linking process, one of the devices will be the "Controller", the other the "Responder" (see e.g. the [SwitchLinc Instructions](https://www.insteon.com/pdf/2477S.pdf)).

The responder listens to messages from the controller, and reacts to them.
Note that except for the case of a motion detector (which is just a controller to the modem), the modem controls the device (e.g. send on/off messages to it), and the device controls the modem (so the modem learns about the switch being toggled.
For this reason, most devices and in particular switches/dimmers should be linked twice, with one taking the role of controller during the first linking, and the other acting as controller during the second linking process.
To do so, first press and hold the "Set" button on the modem until the light starts blinking.
Then press and hold the "Set" button on the remote device,
e.g. the light switch, until it double beeps (the light on the modem should go off as well.
Now do exactly the reverse: press and hold the "Set" button on the remote device until its light starts blinking, then press and hold the "Set" button on the modem until it double beeps, and the light of the remote device (switch) goes off.

For some of the more sophisticated devices the complete linking process can no longer be done with the set buttons, but requires software like [Insteon Terminal](https://github.com/pfrommerd/insteon-terminal).

## Insteon Features

Since Insteon devices can have multiple features (for instance a switchable relay and a contact sensor) under a single Insteon address, an openHAB item is not bound to a device, but to a given feature of a device.
For example, the following lines would create two Number items referring to the same thermostat device, but to different features of it:

```
Number:Temperature  thermostatCoolPoint "cool point [%.1f °F]" { channel="insteon:device:home:32F422:coolSetPoint" }
Number:Temperature  thermostatHeatPoint "heat point [%.1f °F]" { channel="insteon:device:home:32F422:heatSetPoint" }
```

### Simple Light Switches

The following example shows how to configure a simple light switch (2477S) in the .items file:

```
Switch officeLight "office light"  { channel="insteon:device:home:AABBCC:switch" }
```

### Simple Dimmers

Here is how to configure a simple dimmer (2477D) in the .items file:

```
Dimmer kitchenChandelier "kitchen chandelier" { channel="insteon:device:home:AABBCC:dimmer" }
```

For *ON* command requests, the binding uses the device local on level setting to set the dimmer level, the same way it would be set when physically pressing on the dimmer.

### On/Off Outlets

Here's how to configure the top and bottom outlet of the in-wall 2 outlet controller:

```
Switch fOutTop "Front Outlet Top"    <socket> { channel="insteon:device:home:AABBCC:topOutlet" }
Switch fOutBot "Front Outlet Bottom" <socket> { channel="insteon:device:home:AABBCC:bottomOutlet" }
```

This will give you individual control of each outlet.

### Mini Remotes

Link the mini remote to be a controller of the modem by using the set button.
Link all buttons, one after the other.
The 4-button mini remote sends out messages on groups 0x01 - 0x04, each corresponding to one button.
The modem's link database (see [Insteon Terminal](https://github.com/pfrommerd/insteon-terminal)) should look like this:

```
    0000 xx.xx.xx                       xx.xx.xx  RESP  10100010 group: 01 data: 02 2c 41
    0000 xx.xx.xx                       xx.xx.xx  RESP  10100010 group: 02 data: 02 2c 41
    0000 xx.xx.xx                       xx.xx.xx  RESP  10100010 group: 03 data: 02 2c 41
    0000 xx.xx.xx                       xx.xx.xx  RESP  10100010 group: 04 data: 02 2c 41
```

The mini remote buttons cannot be modeled as items since they don't have a state or can receive commands. However, you can monitor button triggered events through rules that can set off subsequent actions:

**Rules**

```
rule "Mini Remote Button A Pressed On"
when
  Channel 'insteon:device:home:miniRemote:eventButtonA' triggered PRESSED_ON
then
  // do something
end
```

### Motion Sensors

Link such that the modem is a responder to the motion sensor.
Create a contact.map file in the transforms directory as described elsewhere in this document.
Then create entries in the .items file like this:

**Items**

```
    Contact              motionSensor             "motion sensor [MAP(contact.map):%s]"   { channel="insteon:device:home:AABBCC:contact"}
    Number:Dimensionless motionSensorBatteryLevel "motion sensor battery level [%.1f %%]" { channel="insteon:device:home:AABBCC:batteryLevel" }
    Number:Dimensionless motionSensorLightLevel   "motion sensor light level [%.1f %%]"   { channel="insteon:device:home:AABBCC:lightLevel" }
```

This will give you a contact, the battery level, and the light level.
Note that battery and light level are only updated when either there is motion, light level above/below threshold, tamper switch activated, or the sensor battery runs low.

The motion sensor II includes additional channels:

**Items**

```
    Contact motionSensorTamperSwitch       "motion sensor tamper switch [MAP(contact.map):%s]" { channel="insteon:device:home:AABBCC:tamperSwitch"}
    Number  motionSensorTemperatureLevel   "motion sensor temperature level"                   { channel="insteon:device:home:AABBCC:temperatureLevel" }
```

The temperature is automatically calculated in Fahrenheit based on the motion sensor II powered source.
Since that sensor might not be calibrated correctly, the output temperature may need to be offset on the openHAB side.

### Hidden Door Sensors

Similar in operation to the motion sensor above.
Link such that the modem is a responder to the motion sensor.
Create a contact.map file in the transforms directory like the following:

```
    OPEN=open
    CLOSED=closed
    -=unknown
```

**Items**

Then create entries in the .items file like this:

```
    Contact              doorSensor             "Door sensor [MAP(contact.map):%s]"   { channel="insteon:device:home:AABBCC:contact" }
    Number:Dimensionless doorSensorBatteryLevel "Door sensor battery level [%.1f %%]" { channel="insteon:device:home:AABBCC:batteryLevel" }
```

This will give you a contact and the battery level.
Note that battery level is only updated when either there is motion, or the sensor battery runs low.

### Locks

Read the instructions very carefully: sync with lock within 5 feet to avoid bad connection, link twice for both ON and OFF functionality.

**Items**

Put something like this into your .items file:

```
    Switch doorLock "Front Door [MAP(lock.map):%s]"  { channel="insteon:device:home:AABBCC:switch" }
```

and create a file "lock.map" in the transforms directory with these entries:

```
    ON=Lock
    OFF=Unlock
    -=unknown
```

### I/O Linc (garage door openers)

The I/O Linc devices are really two devices in one: a relay and a contact.
Link the modem both ways, as responder and controller using the set buttons as described in the instructions.

Add this map into your transforms directory as "contact.map":

```
    OPEN=open
    CLOSED=closed
    -=unknown
```

**Items**

Along with this into your .items file:

```
    Switch  garageDoorOpener  "garage door opener"                        <garagedoor>  { channel="insteon:device:home:AABBCC:switch", autoupdate="false" }
    Contact garageDoorContact "garage door contact [MAP(contact.map):%s]"               { channel="insteon:device:home:AABBCC:contact" }
```

**Sitemap**

To make it visible in the GUI, put this into your sitemap file:

```
    Switch item=garageDoorOpener label="garage door opener" mappings=[ ON="OPEN/CLOSE"]
    Text item=garageDoorContact
```

For safety reasons, only close the garage door if you have visual contact to make sure there is no obstruction! The use of automated rules for closing garage doors is dangerous.

> NOTE: If the I/O Linc returns the wrong value when the device is polled (For example you open the garage door and the state correctly shows OPEN, but during polling it shows CLOSED), you probably linked the device with the PLM or hub when the door was in the wrong position.
You need unlink and then link again with the door in the opposite position.
Please see the Insteon I/O Linc documentation for further details.

### Keypads

Before you attempt to configure the keypads, please familiarize yourself with the concept of an Insteon group.

The Insteon keypad devices typically control one main load and have a number of buttons that will send out group broadcast messages to trigger a scene.
If you just want to use the main load switch within openHAB just link modem and device with the set buttons as usual, no complicated linking is necessary.
But if you want to get the buttons to work, read on.

Each button will send out a message for a different, predefined group.
Complicating matters further, the button numbering used internally by the device must be mapped to whatever labels are printed on the physical buttons of the device.
Here is an example correspondence table:

| Group | Button Number | 2487S Label |
|-------|---------------|-------------|
|  0x01 |        1      |   (Load)    |
|  0x03 |        3      |     A       |
|  0x04 |        4      |     B       |
|  0x05 |        5      |     C       |
|  0x06 |        6      |     D       |

When e.g. the "A" button is pressed (that's button #3 internally) a broadcast message will be sent out to all responders configured to listen to Insteon group #3.
This means you must configure the modem as a responder to group #3 (and #4, #5, #6) messages coming from your keypad.
For instructions how to do this, check out the [Insteon Terminal](https://github.com/pfrommerd/insteon-terminal.
You can even do that with the set buttons (see instructions that come with the keypad).

To accomplish this, you need to pick a set of unused groups that is globally unique (if you have multiple keypads, each one of them has to use different groups), one group for each button.
The example configuration below uses groups 0xf3, 0xf4, 0xf5, and 0xf6.
Then link the buttons such that they respond to those groups, and link the modem as a controller for them (see [Insteon Terminal](https://github.com/pfrommerd/insteon-terminal) documentation.
In your items file you specify these groups with the *group* parameters such that the binding knows what group number to put on the outgoing message.

While previously, keypad buttons required a *group* channel parameter to be setup, the binding can now automatically determine these broadcast groups, based on the relevant device link database, if a valid *group* parameter is not configured.
And it will fallback to just switching the led button on/off when the group cannot be determined because of no links in database for that component defined or no *group* parameter configured.
To force the fallback mechanism on a given button, the *ledOnly* channel parameter set to *true* should be used.
The led button on/off switching supports any keypad local device radio group settings, updating all led buttons related to the requested button.
Additionally, any keypad button toggle mode set to always on will only process *ON* commands, in line with the physical interaction.

#### Keypad Switches

**Items**

Here is an example, using a keypad load (main) switch and associated buttons:

```
    Switch keypadSwitch             "main switch"        { channel="insteon:device:home:AABBCC:switch" }
    String keypadSwitchManualChange "main manual change" { channel="insteon:device:home:AABBCC:manualChange" }
    Switch keypadSwitchFastOnOff    "main fast on/off"   { channel="insteon:device:home:AABBCC:fastOnOff" }
    Switch keypadSwitchA            "button A"           { channel="insteon:device:home:AABBCC:buttonA"}
    Switch keypadSwitchB            "button B"           { channel="insteon:device:home:AABBCC:buttonB"}
    Switch keypadSwitchC            "button C"           { channel="insteon:device:home:AABBCC:buttonC"}
    Switch keypadSwitchD            "button D"           { channel="insteon:device:home:AABBCC:buttonD"}
```


Most people will not use the fast on/off features or the manual change feature, unless you want to send commands to control these features.

**Sitemap**

The following sitemap will bring the items to life in the GUI:

```
    Frame label="Keypad" {
          Switch item=keypadSwitch label="main"
          Switch item=keypadSwitchFastOnOff label="fast on/off" mappings=[ON="FAST ON", OFF="FAST OFF"]
          Switch item=keypadSwitchManualChange label="manual change" mappings=[BRIGHTEN="BRIGHTEN", DIM="DIM", STOP="STOP"]
          Switch item=keypadSwitchA label="button A"
          Switch item=keypadSwitchB label="button B"
          Switch item=keypadSwitchC label="button C"
          Switch item=keypadSwitchD label="button D"
    }
```

**Rules**

The following rules will monitor regular on/off, fast on/off and manual change button events:

```
rule "Main Button Off Event"
when
  Channel 'insteon:device:home:AABBCC:eventButtonMain' triggered PRESSED_OFF
then
  // do something
end

rule "Main Button Fast On/Off Events"
when
  Channel 'insteon:device:home:AABBCC:eventButtonMain' triggered DOUBLE_PRESSED_ON or
  Channel 'insteon:device:home:AABBCC:eventButtonMain' triggered DOUBLE_PRESSED_OFF
then
  // do something
end

rule "Main Button Manual Change Stop Event"
when
  Channel 'insteon:device:home:AABBCC:eventButtonMain' triggered RELEASED
then
  // do something
end

rule "Keypad Button A On Event"
when
  Channel 'insteon:device:home:AABBCC:eventButtonA' triggered PRESSED_ON
then
  // do something
end
```

#### Keypad Dimmers

The keypad dimmers are like keypad switches, except that the main load is dimmable.

**Items**

```
    Dimmer keypadDimmer           "dimmer"                          { channel="insteon:device:home:AABBCC:dimmer" }
    Switch keypadDimmerButtonA    "keypad dimmer button A [%d %%]"  { channel="insteon:device:home:AABBCC:buttonA" }
```

**Sitemap**

```
    Slider item=keypadDimmer switchSupport
    Switch item=keypadDimmerButtonA label="buttonA"
```

### Thermostats

The thermostat (2441TH) is one of the most complex Insteon devices available.
It must first be properly linked to the modem using configuration software like [Insteon Terminal](https://github.com/pfrommerd/insteon-terminal.
The Insteon Terminal wiki describes in detail how to link the thermostat, and how to make it publish status update reports.

When all is set and done the modem must be configured as a controller to group 0 (not sure why), and a responder to groups 1-5 such that it picks up when the thermostat switches on/off heating and cooling etc, and it must be a responder to special group 0xEF to get status update reports when measured values (temperature) change.
Symmetrically, the thermostat must be a responder to group 0, and a controller for groups 1-5 and 0xEF.
The linking process is not difficult but needs some persistence.
Again, refer to the [Insteon Terminal](https://github.com/pfrommerd/insteon-terminal) documentation.

**Items**

This is an example of what to put into your .items file:

```
    Number:Temperature   thermostatCoolPoint   "cool point [%.1f °F]"  { channel="insteon:device:home:AABBCC:coolSetPoint" }
    Number:Temperature   thermostatHeatPoint   "heat point [%.1f °F]"  { channel="insteon:device:home:AABBCC:heatSetPoint" }
    String               thermostatSystemMode  "system mode [%s]"      { channel="insteon:device:home:AABBCC:systemMode" }
    String               thermostatSystemState "system state [%s]"     { channel="insteon:device:home:AABBCC:systemState" }
    String               thermostatFanMode     "fan mode [%s]"         { channel="insteon:device:home:AABBCC:fanMode" }
    Number:Temperature   thermostatTemperature "temperature [%.1f °F]" { channel="insteon:device:home:AABBCC:tempFahrenheit" }
    Number:Dimensionless thermostatHumidity    "humidity [%.0f %%]"    { channel="insteon:device:home:AABBCC:humidity" }
```

Add this as well for some more exotic features:

```
    Number:Time          thermostatACDelay      "A/C delay [%d min]"        { channel="insteon:device:home:AABBCC:acDelay" }
    Number:Time          thermostatBacklight    "backlight [%d sec]"        { channel="insteon:device:home:AABBCC:backlightDuration" }
    Number:Time          thermostatStage1       "A/C stage 1 time [%d min]" { channel="insteon:device:home:AABBCC:stage1Duration" }
    Number:Dimensionless thermostatHumidityHigh "humidity high [%d %%]"     { channel="insteon:device:home:AABBCC:humidityHigh" }
    Number:Dimensionless thermostatHumidityLow  "humidity low [%d %%]"      { channel="insteon:device:home:AABBCC:humidityLow" }
    String               thermostatTempFormat   "temperature format [%s]"   { channel="insteon:device:home:AABBCC:temperatureFormat" }
    String               thermostatTimeFormat   "time format [%s]"          { channel="insteon:device:home:AABBCC:timeFormat" }
```

**Sitemap**

For the thermostat to display in the GUI, add this to the sitemap file:

```
    Text     item=thermostatTemperature icon="temperature"
    Text     item=thermostatHumidity
    Setpoint item=thermostatCoolPoint icon="temperature" minValue=63 maxValue=90 step=1
    Setpoint item=thermostatHeatPoint icon="temperature" minValue=50 maxValue=80 step=1
    Switch   item=thermostatSystemMode mappings=[ OFF="OFF", HEAT="HEAT", COOL="COOL", AUTO="AUTO", PROGRAM="PROGRAM" ]
    Text     item=thermostatSystemState
    Switch   item=thermostatFanMode mappings=[ AUTO="AUTO", ON="ALWAYS ON" ]
    Setpoint item=thermostatACDelay minValue=2 maxValue=20 step=1
    Setpoint item=thermostatBacklight minValue=0 maxValue=100 step=1
    Setpoint item=thermostatHumidityHigh minValue=0 maxValue=100 step=1
    Setpoint item=thermostatHumidityLow  minValue=0 maxValue=100 step=1
    Setpoint item=thermostatStage1 minValue=1 maxValue=60 step=1
    Switch   item=thermostatTempFormat mappings=[ CELSIUS="CELSIUS", FAHRENHEIT="FAHRENHEIT" ]
```

### Power Meters

The iMeter Solo reports both wattage and kilowatt hours, and is updated during the normal polling process of the devices.
You can also manually update the current values from the device and reset the device.
See the example below:

**Items**

```
    Number:Power  iMeterWatts   "iMeter [%d W]"      { channel="insteon:device:home:AABBCC:watts" }
    Number:Energy iMeterKwh     "iMeter [%.04f kWh]" { channel="insteon:device:home:AABBCC:kWh" }
    Switch        iMeterUpdate  "iMeter Update"      { channel="insteon:device:home:AABBCC:update" }
    Switch        iMeterReset   "iMeter Reset"       { channel="insteon:device:home:AABBCC:reset" }
```

### Fan Controllers

Here is an example configuration for a FanLinc module, which has a dimmable light and a variable speed fan:

**Items**

```
    Dimmer fanLincDimmer "fanlinc dimmer [%d %%]" { channel="insteon:device:home:AABBCC:lightDimmer" }
    String fanLincFan    "fanlinc fan"            { channel="insteon:device:home:AABBCC:fanSpeed" }
```

**Sitemap**

```
    Slider item=fanLincDimmer switchSupport
    Switch item=fanLincFan label="fan speed" mappings=[ OFF="OFF", LOW="LOW", MEDIUM="MEDIUM", HIGH="HIGH"]
```

## Battery Powered Devices

Battery powered devices (mostly sensors) work differently than standard wired one.
To conserve battery, these devices are only pollable when there are awake.
Typically they send a heartbeat every 24 hours. When the binding receive a message from one of these devices, it polls additional information needed during the awake period (about 4 seconds).
Some wireless devices have a `stayAwake` channel that can extend the period to 4 minutes but at the cost of using more battery. It shouldn't be in most cases except during initial device configuration.
Same goes with commands, the binding will queue up commands requested on these devices and send them during the awake time window.
Only one command per channel is queued, this mean that the subsequent requests will overwrite the previous ones.

### X10 Devices

It is worth noting that both the Insteon PLM and the 2014 Hub can both command X10 devices over the powerline, and also set switch stats based on X10 signals received over the powerline.
This allows openHAB not only control X10 devices without the need for other hardware, but it can also have rules that react to incoming X10 powerline commands.
While you cannot bind the the X10 devices to the Insteon PLM/HUB, here are some examples for configuring X10 devices.
Be aware that most X10 switches/dimmers send no status updates, i.e. openHAB will not learn about switches that are toggled manually.
Further note that X10 devices are addressed with `houseCode.unitCode`, e.g. `A.2`.

**Things**

```
Bridge insteon:network:home [port="/dev/ttyUSB0"] {
  Thing x10 A2 [houseCode="A", unitCode=2, deviceType="X10_Switch"]
  Thing x10 B4 [houseCode="B", unitCode=4, deviceType="X10_Dimmer"]
  Thing x10 C6 [houseCode="C", unitCode=6, deviceType="X10_Sensor"]
}

```

**Items**

```
    Switch  x10Switch "X10 switch" { channel="insteon:x10:home:A2:switch" }
    Dimmer  x10Dimmer "X10 dimmer" { channel="insteon:x10:home:B4:dimmer" }
    Contact x10Motion "X10 motion" { channel="insteon:x10:home:C6:contact" }
```

## Scenes

The binding can trigger scenes by commanding the modem to send broadcasts to a given Insteon group.

**Things**

```
Bridge insteon:network:home [port="/dev/ttyUSB0"] {
  Thing scene scene42 [group=42]
}

```

**Items**

```
    Switch scene "scene" { channel="insteon:scene:home:scene42:broadcastOnOff" }
```

Flipping this switch to "ON" will cause the modem to send a broadcast message with group=42, and all devices that are configured to respond to it should react.
Because scenes are stateless, the scene item will not receive any state updates and should be used as write only.

## Related Devices

When an Insteon device changes its state because it is directly operated (for example by flipping a switch manually), it sends out a broadcast message to announce the state change, and the binding (if the PLM modem is properly linked as a responder) should update the corresponding openHAB items.
Other linked devices however may also change their state in response, but those devices will *not* send out a broadcast message, and so openHAB will not learn about their state change until the next poll.
One common scenario is e.g. a switch in a 3-way configuration, with one switch controlling the load, and the other switch being linked as a controller.
In this scenario, when the binding receives a broadcast message from one of these devices indicating a state change, it will poll the other related devices shortly after, instead of waiting until the next scheduled device poll which can take minutes.
It is important to note, that the binding will now automatically determine related devices, based on relevant device link database, deprecating the *related* channel parameter.
For scenes, the related devices will be polled, based on the modem database, after sending a group broadcast message.

## Triggered Events

In order to monitor if an Insteon device button was directly operated and the type of interaction, triggered event channels can be used.
These channels have the sole purpose to be used in rules in order to set off subsequent actions based on these events.
Below are examples, including all available events, of a dimmer button and a keypad button:

```
rule "Dimmer Paddle Events"
when
  Channel 'insteon:device:home:dimmer:eventButton' triggered
then
  switch receivedEvent {
    case PRESSED_ON:         // do something (regular on)
    case PRESSED_OFF:        // do something (regular off)
    case DOUBLE_PRESSED_ON:  // do something (fast on)
    case DOUBLE_PRESSED_OFF: // do something (fast off)
    case HELD_UP:            // do something (manual change up)
    case HELD_DOWN:          // do something (manual change down)
    case RELEASED:           // do something (manual change stop)
  }
end

rule "Keypad Button A Pressed Off"
when
  Channel 'insteon:device:home:keypad:eventButtonA' triggered PRESSED_OFF
then
  // do something
end
```

If you previously used `fastOnOff` and `manualChange` channels to monitor these events, make sure to update your rules to use the event channels instead.
These channels are now write only and therefore no longer provide state updates.

## Troubleshooting

Turn on DEBUG or TRACE logging for `org.openhab.binding.insteon.
See [logging in openHAB](https://www.openhab.org/docs/administration/logging.html) for more info.

### Device Permissions / Linux Device Locks

When openHAB is running as a non-root user (Linux/OSX) it is important to ensure it has write access not just to the PLM device, but to the os lock directory.
Under openSUSE this is `/run/lock` and is managed by the **lock** group.

Example commands to grant openHAB access (adjust for your distribution):

````
usermod -a -G dialout openhab
usermod -a -G lock openhab
````

Insufficient access to the lock directory will result in openHAB failing to access the device, even if the device itself is writable.

### Adding New Device Products

If you can't find a product out of the existing device products (for a complete list see `device_products.xml`) you can add new products by specifying a file (let's call it `my_own_products.xml`) in the network config parameters:

    additionalProducts="/usr/local/openhab/rt/my_own_products.xml"

In this file you can define your own products, or even overwrite an existing product.
Each product should have at least a device category and sub category specified. Only use official information or actual data retrieved.
In the example below product devCat=`0xFF` subCat=`0xFF` is added and modeled as `DimmableLightingControl_LampLinc` device type:

```xml
  <xml>
    <product devCat="0xFF" subCat="0xFF">
  		<description>New Dimmer Device</description>
  		<model>New Dimmer Model</model>
  		<device-type>DimmableLightingControl_LampLinc</device-type>
  	</product>
  </xml>
```

### Adding New Device Types

If you can't find a device types out of the existing device types (for a complete list see `device_types.xml`) you can add new device types by specifying a file (e.g. `my_own_devices.xml`) in the network config parameters:

    additionalDeviceTypes="/usr/local/openhab/rt/my_own_devices.xml"

In this file you can define your own device types, or even overwrite an existing device type.
If adding a new device type, an additional product file will need to be created as well to reference the new type to a product.
The device type name should be composed of one of the existing device category name and a device sub category name that characterizes the new device type (e.g. `DimmableLightingControl_LampLinc`).
In the example below device type `DimmableLightingControl_LampLinc` is overwritten, adding a new op flags named `foobar`:

```xml
    <xml>
      <device-type name="DimmableLightingControl_LampLinc">
    		<feature name="dimmer">GenericDimmer</feature>
    		<feature name="fastOnOff">GenericFastOnOff</feature>
    		<feature name="manualChange">GenericManualChange</feature>
    		<feature name="rampDimmer">RampDimmer</feature>
    		<feature name="eventButton">GenericButtonEvent</feature>
    		<feature name="lastHeardFrom">GenericLastTime</feature>
    		<feature name="insteonEngine">InsteonEngine</feature>
    		<feature name="beep">Beep</feature>
    		<feature-group name="extDataGroup" type="ExtDataGroup">
    			<feature name="ledBrightness">LEDBrightness</feature>
    			<feature name="rampRate">RampRate</feature>
    			<feature name="onLevel">OnLevel</feature>
    		</feature-group>
    		<feature-group name="opFlagsGroup" type="OpFlagsGroup">
    			<feature name="programLock" bit="0" on="0x00" off="0x01">OpFlags</feature>
    			<feature name="ledTraffic" bit="1" on="0x02" off="0x03">OpFlags</feature>
    			<feature name="resumeDimLevel" bit="2" on="0x04" off="0x05">OpFlags</feature>
    			<feature name="ledOnOff" bit="4" on="0x09" off="0x08" inverted="true">OpFlags</feature>
    			<feature name="loadSense" bit="5" on="0x0A" off="0x0B">OpFlags</feature>
    			<feature name="foobar" bit="X" on="0xXX" off="0xXX">OpFlags</feature>
    		</feature-group>
    	</device-type>
    </xml>
```

### Adding New Device Features

If you can't find a feature types out of the existing device features (for a complete list see `device_features.xml`) you can add new feature types by specifying a file (let's call it `my_own_features.xml`) in the network config parameters:

    additionalFeatures="/usr/local/openhab/rt/my_own_features.xml"

In this file you can define your own feature types, or even overwrite an existing feature type.
If adding a new feature type, an additional device type file will need to be created as well to reference the new feature to a device type.
In the example below a new feature type `MyFeature` is defined:

```xml
    <xml>
      <feature-type name="MyFeature">
      	<message-dispatcher>DefaultDispatcher</message-dispatcher>
      	<message-handler command="0x11" group="1">NoOpMsgHandler</message-handler>
      	<message-handler command="0x13" group="1">NoOpMsgHandler</message-handler>
      	<message-handler command="0x19">SwitchRequestReplyHandler</message-handler>
      	<message-handler command="0x2f">NoOpMsgHandler</message-handler>
      	<command-handler command="OnOffType">SwitchOnOffCommandHandler</command-handler>
      	<poll-handler ext="0" cmd1="0x19" cmd2="0x00">FlexPollHandler</poll-handler>
      </feature-type>
    </xml>
```

## Known Limitations and Issues

* Devices cannot be linked to the modem while the binding is running.
If new devices are linked, the binding must be restarted.
* Setting up Insteon groups and linking devices cannot be done from within openHAB.
Use the [Insteon Terminal](https://github.com/pfrommerd/insteon-terminal) for that.
If using Insteon Terminal (especially as root), ensure any stale lock files (For example, /var/lock/LCK..ttyUSB0) are removed before starting openHAB runtime.
Failure to do so may result in "found no ports".
* The Insteon PLM or hub is know to break in about 2-3 years due to poorly sized capacitors.
You can repair it yourself using basic soldering skills, search for "Insteon PLM repair" or "Insteon hub repair".
* Using the Insteon Hub 2014 in conjunction with other applications (such as the InsteonApp) is not supported. Concretely, openHAB will not learn when a switch is flipped via the Insteon App until the next poll, which could take minutes.
