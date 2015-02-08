## Cordova BLE Plugin by Rtone

This plugin implements BLE support for Android and iOS. Enable your Cordova and PhoneGap mobile applications to communicate with all sorts of BLE devices.

It's a fork of **Evothings BLE** plugin in order improve Android support. See their [Github repo](https://github.com/evothings/cordova-ble).

Available functionality:

* Scan for BLE devices
* Establish connections
* List services, characteristics and descriptors
* Read and write the values of characteristics and descriptors
* Request notification of changes to the values of characteristics
* Poll RSSI (signal strength) of a device

#### New feature (Android platform only)
 * Create and remove bonding informations.
 * Scan for bonded devices.
 * Establish encrypted connection using passkey strategy
 * Support different connection parameters such as `autoConnect`

### Documentation

Reference documentation is available in the [ble.js](https://github.com/evothings/cordova-ble/blob/master/ble.js) source file.

To build the documentation using [jsdoc](https://github.com/jsdoc3/jsdoc), run this command:

    jsdoc -l -c conf.json ble.js

The file [introduction.md](introduction.md) contains a general introduction to BLE programming.
