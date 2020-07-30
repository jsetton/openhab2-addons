/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.insteon.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.io.transport.serial.SerialPortManager;
import org.openhab.binding.insteon.internal.config.InsteonChannelConfiguration;
import org.openhab.binding.insteon.internal.config.InsteonNetworkConfiguration;
import org.openhab.binding.insteon.internal.database.LinkDB.LinkDBRecord;
import org.openhab.binding.insteon.internal.database.ModemDB.ModemDBEntry;
import org.openhab.binding.insteon.internal.device.DeviceFeature;
import org.openhab.binding.insteon.internal.device.DeviceFeatureListener;
import org.openhab.binding.insteon.internal.device.DeviceTypeLoader;
import org.openhab.binding.insteon.internal.device.InsteonAddress;
import org.openhab.binding.insteon.internal.device.InsteonDevice;
import org.openhab.binding.insteon.internal.device.ProductData;
import org.openhab.binding.insteon.internal.device.ProductDataLoader;
import org.openhab.binding.insteon.internal.device.RequestQueueManager;
import org.openhab.binding.insteon.internal.driver.Driver;
import org.openhab.binding.insteon.internal.driver.DriverListener;
import org.openhab.binding.insteon.internal.driver.Poller;
import org.openhab.binding.insteon.internal.handler.InsteonDeviceHandler;
import org.openhab.binding.insteon.internal.handler.InsteonNetworkHandler;
import org.openhab.binding.insteon.internal.handler.InsteonSceneHandler;
import org.openhab.binding.insteon.internal.message.FieldException;
import org.openhab.binding.insteon.internal.message.Msg;
import org.openhab.binding.insteon.internal.message.MsgListener;
import org.openhab.binding.insteon.internal.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

/**
 * A majority of the code in this file is from the openHAB 1 binding
 * org.openhab.binding.insteonplm.InsteonPLMActiveBinding. Including the comments below.
 *
 * -----------------------------------------------------------------------------------------------
 *
 * This class represents the actual implementation of the binding, and controls the high level flow
 * of messages to and from the InsteonModem.
 *
 * Writing this binding has been an odyssey through the quirks of the Insteon protocol
 * and Insteon devices. A substantial redesign was necessary at some point along the way.
 * Here are some of the hard learned lessons that should be considered by anyone who wants
 * to re-architect the binding:
 *
 * 1) The entries of the link database of the modem are not reliable. The category/subcategory entries in
 * particular have junk data. Forget about using the modem database to generate a list of devices.
 * The database should only be used to verify that a device has been linked.
 *
 * 2) Querying devices for their product information does not work either. First of all, battery operated devices
 * (and there are a lot of those) have their radio switched off, and may generally not respond to product
 * queries. Even main stream hardwired devices sold presently (like the 2477s switch and the 2477d dimmer)
 * don't even have a product ID. Although supposedly part of the Insteon protocol, we have yet to
 * encounter a device that would cough up a product id when queried, even among very recent devices. They
 * simply return zeros as product id. Lesson: forget about querying devices to generate a device list.
 *
 * 3) Polling is a thorny issue: too much traffic on the network, and messages will be dropped left and right,
 * and not just the poll related ones, but others as well. In particular sending back-to-back messages
 * seemed to result in the second message simply never getting sent, without flow control back pressure
 * (NACK) from the modem. For now the work-around is to space out the messages upon sending, and
 * in general poll as infrequently as acceptable.
 *
 * 4) Instantiating and tracking devices when reported by the modem (either from the database, or when
 * messages are received) leads to complicated state management because there is no guarantee at what
 * point (if at all) the binding configuration will be available. It gets even more difficult when
 * items are created, destroyed, and modified while the binding runs.
 *
 * For the above reasons, devices are only instantiated when they are referenced by binding information.
 * As nice as it would be to discover devices and their properties dynamically, we have abandoned that
 * path because it had led to a complicated and fragile system which due to the technical limitations
 * above was inherently squirrely.
 *
 *
 * @author Bernd Pfrommer - Initial contribution
 * @author Daniel Pfrommer - openHAB 1 insteonplm binding
 * @author Rob Nielsen - Port to openHAB 2 insteon binding
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
@SuppressWarnings("null")
public class InsteonBinding {
    private static final int DEAD_DEVICE_COUNT = 10;

    private final Logger logger = LoggerFactory.getLogger(InsteonBinding.class);

    private Driver driver;
    private ConcurrentHashMap<InsteonAddress, InsteonDevice> devices = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer, InsteonSceneHandler> sceneHandlers = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, InsteonChannelConfiguration> channelConfigs = new ConcurrentHashMap<>();
    private int devicePollIntervalMilliseconds = -1;
    private int deadDeviceTimeout = -1;
    private int messagesReceived = 0;
    private boolean isActive = false; // state of binding
    private int x10HouseUnit = -1;
    private InsteonNetworkHandler handler;

    public InsteonBinding(InsteonNetworkHandler handler, @Nullable InsteonNetworkConfiguration config,
            @Nullable SerialPortManager serialPortManager, ScheduledExecutorService scheduler) {
        this.handler = handler;

        String port = config.getPort();
        logger.debug("port = '{}'", StringUtils.redactPassword(port));

        PortListener portListener = new PortListener();
        driver = new Driver(port, portListener, serialPortManager, scheduler);

        devicePollIntervalMilliseconds = config.getDevicePollIntervalSeconds() * 1000;
        logger.debug("device poll interval set to {} seconds", devicePollIntervalMilliseconds / 1000);

        String additionalDeviceTypes = config.getAdditionalDeviceTypes();
        if (additionalDeviceTypes != null) {
            try {
                DeviceTypeLoader.instance().loadDeviceTypesXML(additionalDeviceTypes);
                logger.debug("read additional device type definitions from {}", additionalDeviceTypes);
            } catch (ParserConfigurationException | SAXException | IOException e) {
                logger.warn("error reading additional device types from {}", additionalDeviceTypes, e);
            }
        }

        String additionalFeatures = config.getAdditionalFeatures();
        if (additionalFeatures != null) {
            logger.debug("reading additional feature templates from {}", additionalFeatures);
            DeviceFeature.readFeatureTemplates(additionalFeatures);
        }

        String additionalProducts = config.getAdditionalProducts();
        if (additionalProducts != null) {
            try {
                ProductDataLoader.instance().loadDeviceProductsXML(additionalProducts);
                logger.debug("read additional product definitions from {}", additionalProducts);
            } catch (ParserConfigurationException | SAXException | IOException e) {
                logger.warn("error reading additional products from {}", additionalProducts, e);
            }
        }

        deadDeviceTimeout = devicePollIntervalMilliseconds * DEAD_DEVICE_COUNT;
        logger.debug("dead device timeout set to {} seconds", deadDeviceTimeout / 1000);
    }

    public Driver getDriver() {
        return driver;
    }

    public void setIsActive(boolean isActive) {
        this.isActive = isActive;
    }

    /**
     * Starts binding polling
     *
     * @return true if driver is running
     */
    public boolean startPolling() {
        logger.debug("starting to poll {}", driver.getPortName());
        driver.start();
        return driver.isRunning();
    }

    /**
     * Reconnect to driver port
     *
     * @return true if successful
     */
    public boolean reconnect() {
        driver.stop();
        return startPolling();
    }

    /**
     * Clean up all state.
     */
    public void shutdown() {
        logger.debug("shutting down Insteon bridge");
        driver.stop();
        devices.clear();
        RequestQueueManager.destroyInstance();
        Poller.instance().stop();
        isActive = false;
    }

    /**
     * Sends command to specific device
     *
     * @param channelName the command channel name
     * @param command     the command to send
     */
    public void sendCommand(String channelName, Command command) {
        if (!isActive) {
            logger.debug("not ready to handle commands yet, returning.");
            return;
        }

        InsteonChannelConfiguration channelConfig = getChannelConfig(channelName);
        if (channelConfig == null) {
            logger.warn("unable to find binding config for channel {}", channelName);
            return;
        }

        DeviceFeature f = channelConfig.getFeature();
        if (f.hasFeatureListener(channelName)) {
            f.handleCommand(channelConfig, command);
        }
    }

    /**
     * Adds feature listener when a channel is linked
     *
     * @param channelUID the channel UID
     * @param feature    the channel feature
     * @param params     the channel parameters
     */
    public void addFeatureListener(ChannelUID channelUID, DeviceFeature feature, Map<String, Object> params) {
        String channelName = channelUID.getAsString();

        if (logger.isDebugEnabled()) {
            logger.debug("adding listener for channel {}", channelName);
        }

        if (feature.hasFeatureListener(channelName)) {
            logger.debug("channel {} already configured", channelName);
            return;
        }

        InsteonChannelConfiguration channelConfig = new InsteonChannelConfiguration(channelUID, feature, params);
        DeviceFeatureListener listener = new DeviceFeatureListener(this, channelConfig);
        feature.addListener(listener);

        channelConfigs.put(channelName, channelConfig);
    }

    /**
     * Removes feature listener when a channel is unlinked
     *
     * @param channelUID the channel UID
     */
    public void removeFeatureListener(ChannelUID channelUID) {
        String channelName = channelUID.getAsString();

        if (logger.isDebugEnabled()) {
            logger.debug("removing listener for channel {}", channelName);
        }

        InsteonChannelConfiguration channelConfig = getChannelConfig(channelName);
        if (channelConfig == null) {
            logger.warn("unable to find binding config for channel {}", channelName);
            return;
        }

        DeviceFeature f = channelConfig.getFeature();
        if (f.removeListener(channelName)) {
            if (logger.isTraceEnabled()) {
                logger.trace("removed feature listener {} from device {}", channelName, f.getDevice().getAddress());
            }
        }

        channelConfigs.remove(channelName);
    }

    /**
     * Updates feature channel state
     *
     * @param channelUID the channel UID
     * @param state      the channel state
     */
    public void updateFeatureState(ChannelUID channelUID, State state) {
        handler.updateState(channelUID, state);
    }

    /**
     * Triggers feature channel event
     *
     * @param channelUID the channel UID
     * @param event      the channel event
     */
    public void triggerFeatureEvent(ChannelUID channelUID, String event) {
        handler.triggerChannel(channelUID, event);
    }

    /**
     * Creates a new InsteonDevice
     *
     * @param  devHandler  the device handler
     * @param  addr        the device address
     * @param  productData the device product data
     * @return             newly created InsteonDevice
     */
    public @Nullable InsteonDevice makeNewDevice(InsteonDeviceHandler devHandler, InsteonAddress addr,
            @Nullable ProductData productData) {
        InsteonDevice dev = InsteonDevice.makeDevice(driver, addr, productData);
        if (dev != null) {
            dev.setHandler(devHandler);
            // initialize device if modem db complete
            if (isModemDBComplete()) {
                dev.initialize(devicePollIntervalMilliseconds);
            }
            handler.insteonDeviceWasCreated();
        }

        return (dev);
    }

    /**
     * Adds a device
     *
     * @param addr the device address
     * @param dev  the device to add
     */
    public void addDevice(InsteonAddress addr, InsteonDevice dev) {
        devices.put(addr, dev);
    }

    /**
     * Gets a device for a given String address
     *
     * @param addr the insteon address to search for
     * @return     reference to the device, or null if not found
     */
    public @Nullable InsteonDevice getDevice(String addr) {
        return InsteonAddress.isValid(addr) ? devices.get(InsteonAddress.parseAddress(addr)) : null;
    }

    /**
     * Gets a device for given InsteonAddress object
     *
     * @param addr the insteon address to search for
     * @return     reference to the device, or null if not found
     */
    public @Nullable InsteonDevice getDevice(@Nullable InsteonAddress addr) {
        return addr != null ? devices.get(addr) : null;
    }

    /**
     * Removes a device
     *
     * @param addr the device address to remove
     */
    public void removeDevice(InsteonAddress addr) {
        InsteonDevice dev = devices.remove(addr);
        if (dev != null) {
            dev.stopPolling();
        }
    }

    /**
     * Adds a scene handler
     *
     * @param group         scene group number
     * @param sceneHandler  scene handler
     */
    public void addSceneHandler(int group, InsteonSceneHandler sceneHandler) {
        sceneHandlers.put(group, sceneHandler);
    }

    /**
     * Finds a scene handler by group number
     *
     * @param  group scene group number to search
     * @return       scene handler if found, otherwise null
     */
    public @Nullable InsteonSceneHandler getSceneHandler(int group) {
        return sceneHandlers.get(group);
    }

    /**
     * Removes a scene handler
     *
     * @param group scene group number
     */
    public void removeSceneHandler(int group) {
        sceneHandlers.remove(group);
    }

    /**
     * Gets a channel configuration for a gvien name
     *
     * @param  name the channel name to search for
     * @return      reference to the channel config, or null if not found
     */
    public @Nullable InsteonChannelConfiguration getChannelConfig(String name) {
        return channelConfigs.get(name);
    }

    /**
     * Gets the modem device
     *
     * @return the modem device
     */
    public @Nullable InsteonDevice getModemDevice() {
        return driver.getModemDevice();
    }

    /**
     * Returns if the driver modem database is complete
     *
     * @return true if modem database complete
     */
    public boolean isModemDBComplete()  {
        return driver.isModemDBComplete();
    }

    /**
     * Returns if a broadcast group is valid
     *
     * @param  group the broadcast group
     * @return       true if the broadcast group exists in modem database
     */
    public boolean isValidBroadcastGroup(int group) {
        return driver.getModemDB().hasBroadcastGroup(group);
    }

    /**
     * Gets list of missing devices
     *
     * @return list of missing device addresses and product data
     */
    public Map<String, @Nullable ProductData> getMissingDevices() {
        Map<String, @Nullable ProductData> missingDevices = new HashMap<>();
        for (InsteonAddress addr : driver.getModemDB().getAddresses()) {
            if (!devices.containsKey(addr)) {
                ModemDBEntry dbe = driver.getModemDB().getEntry(addr);
                logger.debug("device {} found in the modem database, but is not configured as a thing.", addr);
                missingDevices.put(addr.toString(), dbe.getProductData());
            }
        }
        return missingDevices;
    }

    /**
     * Gets a list of missing scenes
     *
     * @return list of missing scene groups
     */
    public List<Integer> getMissingScenes() {
        List<Integer> missingScenes = new ArrayList<>();
        for (int group : driver.getModemDB().getBroadcastGroups()) {
            if (!sceneHandlers.containsKey(group)) {
                logger.debug("scene group {} found in the modem database, but is not configured as a thing.", group);
                missingScenes.add(group);
            }
        }
        return missingScenes;
    }

    /**
     * Gets list of available channels information
     *
     * @return the list available channels information
     */
    public Map<String, String> getChannelsInfo() {
        Map<String, String> channelsInfo = new HashMap<>();
        for (InsteonDevice dev : devices.values()) {
            InsteonDeviceHandler devHandler = dev.getHandler();
            channelsInfo.putAll(devHandler.getChannelsInfo());
        }
        for (InsteonSceneHandler sceneHandler : sceneHandlers.values()) {
            channelsInfo.putAll(sceneHandler.getChannelsInfo());
        }
        return channelsInfo;
    }

    /**
     * Gets list of configured devices information
     *
     * @return the list of configured devices information
     */
    public Map<String, String> getDevicesInfo() {
        Map<String, String> devicesInfo = new HashMap<>();
        for (InsteonDevice dev : devices.values()) {
            InsteonDeviceHandler devHandler = dev.getHandler();
            devicesInfo.put(devHandler.getThing().getUID().getAsString(), devHandler.getThingInfo()
                    + " status = " + devHandler.getThing().getStatus());
        }
        return devicesInfo;
    }

    /**
     * Gets list of configured scenes information
     *
     * @return the list of configured scenes information
     */
    public Map<String, String> getScenesInfo() {
        Map<String, String> scenesInfo = new HashMap<>();
        for (InsteonSceneHandler sceneHandler : sceneHandlers.values()) {
            scenesInfo.put(sceneHandler.getThing().getUID().getAsString(), sceneHandler.getThingInfo()
                    + " status = " + sceneHandler.getThing().getStatus());
        }
        return scenesInfo;
    }

    /**
     * Gets specific device link database information
     *
     * @param  address the device address
     * @return         the list of link db records relevant to device
     */
    public @Nullable List<String> getDeviceDBInfo(String address) {
        InsteonDevice dev = getDevice(address);
        if (dev != null) {
            List<String> deviceDBInfo = new ArrayList<>();
            for (LinkDBRecord record : dev.getLinkDB().getRecords()) {
                deviceDBInfo.add(record.toString());
            }
            return deviceDBInfo;
        }
        return null;
    }

    /**
     * Gets specific device product data information
     *
     * @param  address the device address
     * @return         the product data information relavant to device
     */
    public @Nullable String getDeviceProductData(String address) {
        InsteonDevice dev = getDevice(address);
        if (dev != null) {
            return dev.getProductData().toString();
        }
        return null;
    }

    /**
     * Gets modem database information
     *
     * @return the list of modem db entries
     */
    public Map<String, String> getModemDBInfo() {
        Map<String, String> modemDBInfo = new HashMap<>();
        for (ModemDBEntry dbe : driver.getModemDB().getEntries()) {
            modemDBInfo.put(dbe.getAddress().toString(), dbe.toString());
        }
        return modemDBInfo;
    }

    /**
     * Method to logs configured devices
     */
    public void logDevices() {
        if (logger.isDebugEnabled()) {
            logger.debug("configured {} devices:", devices.size());
            for (InsteonDevice dev : devices.values()) {
                logger.debug("{}", dev);
            }
        }
    }

    /**
     * Method to log device statistics
     */
    public void logDeviceStatistics() {
        if (logger.isDebugEnabled()) {
            String msg = String.format("devices: %3d configured, %3d polling, msgs received: %5d", devices.size(),
                    Poller.instance().getSizeOfQueue(), messagesReceived);
            logger.debug("{}", msg);
            messagesReceived = 0;
            for (InsteonDevice dev : devices.values()) {
                if (deadDeviceTimeout > 0 && dev.getPollOverDueTime() > deadDeviceTimeout) {
                    logger.debug("device {} has not responded to polls for {} sec", dev.toString(),
                            dev.getPollOverDueTime() / 3600);
                }
            }
        }
    }

    /**
     * Handles messages that come in from the ports.
     * Will only process one message at a time.
     */
    @NonNullByDefault
    private class PortListener implements MsgListener, DriverListener {
        @Override
        public void msg(Msg msg) {
            if (msg.isEcho()) {
                return;
            }
            messagesReceived++;
            if (msg.isX10()) {
                handleX10Message(msg);
            } else {
                handleInsteonMessage(msg);
            }
        }

        @Override
        public void disconnected() {
            handler.bindingDisconnected();
        }

        @Override
        public void modemDBComplete() {
            // add port message listener
            driver.addMsgListener(this);

            for (InsteonDevice dev : devices.values()) {
                logger.trace("initializing device {}", dev.getAddress());
                dev.initialize(devicePollIntervalMilliseconds);
            }

            for (InsteonSceneHandler sceneHandler : sceneHandlers.values()) {
                sceneHandler.update();
            }

            // log devices
            logDevices();
            // discover missing things
            handler.discoverMissingThings();
        }

        @Override
        public void modemDBUpdated(InsteonAddress addr, int group) {
            logger.debug("modem database link updated for device {} group {}", addr, group);
            InsteonDevice dev = getDevice(addr);
            if (dev != null) {
                // (re)initialize updated device
                dev.initialize(devicePollIntervalMilliseconds);
            }
            InsteonSceneHandler sceneHandler = getSceneHandler(group);
            if (sceneHandler != null) {
                // update related group scene thing status
                sceneHandler.updateThingStatus();
            }
        }

        @Override
        public void modemFound() {
            InsteonDevice dev = getModemDevice();
            logger.debug("found modem {}", dev);
        }

        @Override
        public void productDataUpdated(InsteonAddress addr) {
            if (!isModemDBComplete()) {
                return;
            }
            InsteonDevice dev = getDevice(addr);
            ProductData productData = driver.getModemDB().getProductData(addr);
            if (dev != null && productData != null) {
                dev.updateProductData(productData);
            }
        }

        @Override
        public void requestSent(InsteonAddress addr, long time) {
            InsteonDevice dev = getDevice(addr);
            if (dev != null) {
                dev.handleRequestSent(time);
            }
        }

        private void handleInsteonMessage(Msg msg) {
            InsteonAddress toAddr = msg.getAddressOrNull("toAddress");
            if (!msg.isBroadcast() && !driver.isMsgForUs(toAddr)) {
                // not for our modem, do not process
                return;
            }
            InsteonAddress fromAddr = msg.getAddressOrNull("fromAddress");
            if (fromAddr == null) {
                logger.debug("invalid fromAddress, ignoring msg {}", msg);
                return;
            }
            handleMessage(fromAddr, msg);
        }

        private void handleX10Message(Msg msg) {
            try {
                int x10Flag = msg.getInt("X10Flag");
                int rawX10 = msg.getInt("rawX10");
                if (x10Flag == 0x80) { // actual command
                    if (x10HouseUnit != -1) {
                        InsteonAddress fromAddr = new InsteonAddress((byte) x10HouseUnit);
                        handleMessage(fromAddr, msg);
                    }
                } else if (x10Flag == 0) {
                    // what unit the next cmd will apply to
                    x10HouseUnit = rawX10 & 0xFF;
                }
            } catch (FieldException e) {
                logger.warn("got bad X10 message: {}", msg, e);
                return;
            }
        }

        private void handleMessage(InsteonAddress fromAddr, Msg msg) {
            InsteonDevice dev = getDevice(fromAddr);
            if (dev == null) {
                logger.debug("dropping message from unknown device with address {}", fromAddr);
            } else {
                dev.handleMessage(msg);
            }
        }
    }
}
