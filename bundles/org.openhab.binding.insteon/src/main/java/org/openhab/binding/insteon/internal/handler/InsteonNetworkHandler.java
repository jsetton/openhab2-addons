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
package org.openhab.binding.insteon.internal.handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.io.console.Console;
import org.eclipse.smarthome.io.transport.serial.SerialPortManager;
import org.openhab.binding.insteon.internal.InsteonBinding;
import org.openhab.binding.insteon.internal.config.InsteonNetworkConfiguration;
import org.openhab.binding.insteon.internal.discovery.InsteonDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link InsteonNetworkHandler} is responsible for handling insteon network bridge
 *
 * @author Rob Nielsen - Initial contribution
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
@SuppressWarnings("null")
public class InsteonNetworkHandler extends BaseBridgeHandler {
    private static final int LOG_DEVICE_STATISTICS_DELAY_IN_SECONDS = 600;
    private static final int RETRY_DELAY_IN_SECONDS = 30;
    private static final int SETTLE_TIME_IN_SECONDS = 5;

    private final Logger logger = LoggerFactory.getLogger(InsteonNetworkHandler.class);

    private @Nullable InsteonNetworkConfiguration config;
    private @Nullable InsteonBinding insteonBinding;
    private @Nullable InsteonDiscoveryService insteonDiscoveryService;
    private @Nullable ScheduledFuture<?> pollingJob = null;
    private @Nullable ScheduledFuture<?> reconnectJob = null;
    private @Nullable ScheduledFuture<?> settleJob = null;
    private long lastInsteonDeviceCreatedTimestamp = 0;
    private @Nullable SerialPortManager serialPortManager;

    public static ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

    public InsteonNetworkHandler(Bridge bridge, @Nullable SerialPortManager serialPortManager) {
        super(bridge);
        this.serialPortManager = serialPortManager;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
    }

    @Override
    public void initialize() {
        logger.debug("Starting Insteon bridge");
        config = getConfigAs(InsteonNetworkConfiguration.class);
        updateStatus(ThingStatus.UNKNOWN);

        scheduler.execute(() -> {
            insteonBinding = new InsteonBinding(this, config, serialPortManager, scheduler);

            // hold off on starting to poll until devices that already are defined as things are added.
            // wait SETTLE_TIME_IN_SECONDS to start then check every second afterwards until it has been at
            // least SETTLE_TIME_IN_SECONDS since last device was created.
            settleJob = scheduler.scheduleWithFixedDelay(() -> {
                // check to see if it has been at least SETTLE_TIME_IN_SECONDS since last device was created
                if (System.currentTimeMillis() - lastInsteonDeviceCreatedTimestamp > SETTLE_TIME_IN_SECONDS * 1000) {
                    // settle time has expired start polling
                    if (insteonBinding.startPolling()) {
                        pollingJob = scheduler.scheduleWithFixedDelay(() -> {
                            insteonBinding.logDeviceStatistics();
                        }, 0, LOG_DEVICE_STATISTICS_DELAY_IN_SECONDS, TimeUnit.SECONDS);

                        insteonBinding.setIsActive(true);

                        updateStatus(ThingStatus.ONLINE);
                    } else {
                        String msg = "Initialization failed, unable to start the Insteon bridge with the port '"
                                + config.getPort() + "'.";
                        logger.warn(msg);

                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                    }

                    settleJob.cancel(false);
                    settleJob = null;
                }
            }, SETTLE_TIME_IN_SECONDS, 1, TimeUnit.SECONDS);
        });
    }

    @Override
    public void dispose() {
        logger.debug("shutting down Insteon bridge");

        if (pollingJob != null) {
            pollingJob.cancel(true);
            pollingJob = null;
        }

        if (reconnectJob != null) {
            reconnectJob.cancel(true);
            reconnectJob = null;
        }

        if (settleJob != null) {
            settleJob.cancel(true);
            settleJob = null;
        }

        if (insteonBinding != null) {
            insteonBinding.shutdown();
            insteonBinding = null;
        }

        super.dispose();
    }

    @Override
    public void updateState(ChannelUID channelUID, State state) {
        super.updateState(channelUID, state);
    }

    @Override
    public void triggerChannel(ChannelUID channelUID, String event) {
        super.triggerChannel(channelUID, event);
    }

    public void bindingDisconnected() {
        reconnectJob = scheduler.scheduleWithFixedDelay(() -> {
            if (insteonBinding.reconnect()) {
                updateStatus(ThingStatus.ONLINE);
                reconnectJob.cancel(false);
                reconnectJob = null;
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Port disconnected.");
            }
        }, 0, RETRY_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    }

    public void insteonDeviceWasCreated() {
        lastInsteonDeviceCreatedTimestamp = System.currentTimeMillis();
    }

    public @Nullable InsteonBinding getInsteonBinding() {
        return insteonBinding;
    }

    public void setInsteonDiscoveryService(InsteonDiscoveryService insteonDiscoveryService) {
        this.insteonDiscoveryService = insteonDiscoveryService;
    }

    public void discoverMissingThings() {
      scheduler.execute(() -> {
          insteonDiscoveryService.discoverMissingThings();
      });
    }

    public boolean isDeviceDiscoveryEnabled() {
        return config.getDeviceDiscoveryEnabled();
    }

    public boolean isSceneDiscoveryEnabled() {
        return config.getSceneDiscoveryEnabled();
    }

    public void displayDevices(Console console) {
        Map<String, String> devicesInfo = insteonBinding.getDevicesInfo();
        if (devicesInfo.isEmpty()) {
            console.println("No device configured!");
        } else {
            console.println("There are " + devicesInfo.size() + " devices configured:");
            display(console, devicesInfo);
        }
    }

    public void displayScenes(Console console) {
        Map<String, String> scenesInfo = insteonBinding.getScenesInfo();
        if (scenesInfo.isEmpty()) {
            console.println("No scene configured!");
        } else {
            console.println("There are " + scenesInfo.size() + " scenes configured:");
            display(console, scenesInfo);
        }
    }

    public void displayChannels(Console console) {
        Map<String, String> channelsInfo = insteonBinding.getChannelsInfo();
        if (channelsInfo.isEmpty()) {
            console.println("No channel available!");
        } else {
            console.println("There are " + channelsInfo.size() + " channels available:");
            display(console, channelsInfo);
        }
    }

    public void displayDeviceDatabase(Console console, String address) {
        List<String> deviceDBInfo = insteonBinding.getDeviceDBInfo(address);
        if (deviceDBInfo == null) {
            console.println("The device address is not valid or configured!");
        } else if (deviceDBInfo.isEmpty()) {
            console.println("The all-link database for device " + address + " is empty");
        } else {
            console.println("The all-link database for device " + address + " contains "
                    + deviceDBInfo.size() + " records:");
            display(console, deviceDBInfo);
        }
    }

    public void displayDeviceProductData(Console console, String address) {
        String deviceProductData = insteonBinding.getDeviceProductData(address);
        if (deviceProductData == null) {
            console.println("The device address is not valid or configured!");
        } else {
            console.println("The product data for device " + address + " is: " + deviceProductData);
        }
    }

    public void displayModemDatabase(Console console) {
        Map<String, String> modemDBInfo = insteonBinding.getModemDBInfo();
        if (modemDBInfo.isEmpty()) {
            console.println("The modem database is empty");
        } else {
            console.println("The modem database contains " + modemDBInfo.size() + " entries:");
            display(console, modemDBInfo);
        }
    }

    private void display(Console console, List<String> info) {
        for (String line : info) {
            console.println(line);
        }
    }

    private void display(Console console, Map<String, String> info) {
        List<String> keys = new ArrayList<>(info.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            console.println(info.get(key));
        }
    }
}
