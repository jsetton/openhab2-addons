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
package org.openhab.binding.insteon.internal.device;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.State;
import org.openhab.binding.insteon.internal.InsteonBinding;
import org.openhab.binding.insteon.internal.config.InsteonChannelConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A DeviceFeatureListener essentially represents an openHAB item that
 * listens to a particular feature of an Insteon device
 *
 * @author Daniel Pfrommer - Initial contribution
 * @author Bernd Pfrommer - openHAB 1 insteonplm binding
 * @author Rob Nielsen - Port to openHAB 2 insteon binding
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
public class DeviceFeatureListener {
    private final Logger logger = LoggerFactory.getLogger(DeviceFeatureListener.class);

    public enum StateChangeType {
        ALWAYS,
        CHANGED
    };

    private static final int TIME_DELAY_POLL_RELATED_MSEC = 2000;

    private InsteonBinding binding;
    private InsteonChannelConfiguration config;
    private Map<Class<?>, @Nullable State> state = new HashMap<>();

    /**
     * Constructor
     *
     * @param binding reference to binding
     * @param config channel config related to this feature listener
     */
    public DeviceFeatureListener(InsteonBinding binding, InsteonChannelConfiguration config) {
        this.binding = binding;
        this.config = config;
        updateChannelConfig();
    }

    /**
     * Gets listener channel name
     *
     * @return name
     */
    public String getChannelName() {
        return config.getChannelName();
    }

    /**
     * Publishes a state change on the openHAB bus
     *
     * @param newState the new state to publish
     * @param changeType whether to always publish or not
     */
    public void stateChanged(State newState, StateChangeType changeType) {
        State oldState = state.get(newState.getClass());
        if (oldState == null) {
            if (logger.isTraceEnabled()) {
                logger.trace("new state: {}:{} old state: null", newState.getClass().getSimpleName(), newState);
            }
            // state has changed, must publish
            publishState(newState);
        } else {
            if (logger.isTraceEnabled()) {
                logger.trace("new state: {}:{} old state: {}:{}", newState.getClass().getSimpleName(), newState,
                        oldState.getClass().getSimpleName(), oldState);
            }
            // only publish if state has changed or it is requested explicitly
            if (changeType == StateChangeType.ALWAYS || !oldState.equals(newState)) {
                publishState(newState);
            }
        }
        state.put(newState.getClass(), newState);
    }

    /**
     * Publish the state. In the case of PercentType, if the value is
     * 0, send a OnOffType.OFF and if the value is 100, send a OnOffType.ON.
     * That way an openHAB Switch will work properly with a Insteon dimmer,
     * as long it is used like a switch (On/Off). An openHAB DimmerItem will
     * internally convert the ON back to 100% and OFF back to 0, so there is
     * no need to send both 0/OFF and 100/ON.
     *
     * @param state the new state of the feature
     */
    private void publishState(State state) {
        State publishState = state;
        if (state instanceof PercentType) {
            if (state.equals(PercentType.ZERO)) {
                publishState = OnOffType.OFF;
            } else if (state.equals(PercentType.HUNDRED)) {
                publishState = OnOffType.ON;
            }
        }
        binding.updateFeatureState(config.getChannelUID(), publishState);
    }

    /**
     * Triggers a channel event on the openHAB bus
     *
     * @param event the name of the event to trigger
     */
    public void triggerEvent(String event) {
        binding.triggerFeatureEvent(config.getChannelUID(), event);
    }

    /**
     * Updates channel config
     */
    public void updateChannelConfig() {
        // update related devices
        updateRelatedDevices();
        // update broadcast group
        updateBroadcastGroup();
    }

    /**
     * Updates broadcast groups channel config
     * based on "group" channel parameter or device link database
     */
    private void updateBroadcastGroup() {
        DeviceFeature f = config.getFeature();
        InsteonDevice dev = f.getDevice();
        if (config.hasParameter("group")) {
            if (binding.isModemDBComplete()) {
                int group = config.getIntParameter("group", -1);
                if (binding.getDriver().getModemDB().hasBroadcastGroup(group)) {
                    config.setBroadcastGroup(group);
                } else {
                    logger.warn("broadcast group {} not found in modem db on channel {}", group, getChannelName());
                }
            }
        } else if (!dev.isModem()) {
            if (dev.getLinkDB().isComplete() && !config.getRelatedDevices().isEmpty()) {
                // iterate over device link db broadcast groups based on "group" feature parameter as component id
                int componentId = f.getIntParameter("group", 1);
                for (int group : dev.getLinkDB().getBroadcastGroups(componentId)) {
                    // compare related devices channel config with the modem db for a given broadcast group
                    List<InsteonAddress> devices = binding.getDriver().getModemDB().getRelatedDevices(group);
                    devices.remove(dev.getAddress());
                    devices.removeAll(config.getRelatedDevices());
                    // set broadcast group if two lists identical
                    if (devices.isEmpty()) {
                        config.setBroadcastGroup(group);
                        break;
                    }
                }
            }
        }
    }

    /**
     * Updates related devices channel config
     * based on device/modem link database
     */
    private void updateRelatedDevices() {
        DeviceFeature f = config.getFeature();
        InsteonDevice dev = f.getDevice();
        if (config.hasParameter("group")) {
            if (binding.isModemDBComplete()) {
                // set devices using "group" channel config parameter to search in modem database if complete
                int group = config.getIntParameter("group", -1);
                List<InsteonAddress> devices = binding.getDriver().getModemDB().getRelatedDevices(group);
                config.setRelatedDevices(devices);
            }
        } else if (!dev.isModem()) {
            if (dev.getLinkDB().isComplete()) {
                // set devices using "group" feature parameter to search in device link database if complete
                int group = f.getIntParameter("group", 1);
                List<InsteonAddress> devices = dev.getLinkDB().getRelatedDevices(group);
                config.setRelatedDevices(devices);
            }
        }
    }

    /**
     * Adjusts related devices for a given command
     *
     * @param cmd the command to adjust to
     */
    public void adjustRelatedDevices(Command cmd) {
        // DeviceFeature f = config.getFeature();
        // InsteonAddress addr =
        // for (InsteonAddress raddr : config.getRelatedDevices()) {
        //     logger.debug("adjusting related device {}", raddr);
        //     InsteonDevice dev = binding.getDevice(raddr);
        //     if (dev != null) {
        //
        //         DeviceFeature rf = dev.getRelatedFeature(f.getDevice().getAddress(), f.getIntParameter("group", 1));
        //         if (rf != null) {
        //         InsteonChannelConfiguration conf =
        //
        //           }
        //             int componentId = dev.getLinkDB().getRespon
        //
        //         }
        //     } else {
        //         logger.warn("device {} related to channel {} is not configured!", addr, getChannelName());
        //     }
        // }
    }

    /**
     * Polls all devices that are related to this channel
     */
    public void pollRelatedDevices() {
        for (InsteonAddress addr : config.getRelatedDevices()) {
            if (logger.isDebugEnabled()) {
                logger.debug("polling related device {} in {} msec", addr, TIME_DELAY_POLL_RELATED_MSEC);
            }
            InsteonDevice dev = binding.getDevice(addr);
            if (dev != null) {
                // poll related device limiting to responder features
                dev.doPollResponders(TIME_DELAY_POLL_RELATED_MSEC);
            } else {
                logger.warn("device {} related to channel {} is not configured!", addr, getChannelName());
            }
        }
    }
}
