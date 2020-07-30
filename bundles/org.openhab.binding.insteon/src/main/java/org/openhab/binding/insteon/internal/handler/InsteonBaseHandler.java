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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.insteon.internal.InsteonBinding;
import org.openhab.binding.insteon.internal.device.DeviceFeature;
import org.openhab.binding.insteon.internal.device.InsteonDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link InsteonBaseHandler} is the base handler for insteon things.
 *
 * @author Jeremy Setton - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings("null")
public abstract class InsteonBaseHandler extends BaseThingHandler {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    private @Nullable InsteonDevice device = null;

    public InsteonBaseHandler(Thing thing) {
        super(thing);
    }

    public @Nullable InsteonDevice getDevice() {
        return device;
    }

    public @Nullable InsteonNetworkHandler getInsteonNetworkHandler() {
        return (InsteonNetworkHandler) getBridge().getHandler();
    }

    public @Nullable InsteonBinding getInsteonBinding() {
        return getInsteonNetworkHandler().getInsteonBinding();
    }

    public void setDevice(@Nullable InsteonDevice device) {
        this.device = device;
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        Channel channel = getThing().getChannel(channelUID.getId());
        String channelName = channelUID.getAsString();
        String featureName = channel.getChannelTypeUID().getId();
        Map<String, Object> params = new HashMap<>(channel.getConfiguration().getProperties());

        DeviceFeature feature = getDevice().getFeature(featureName);
        if (feature == null) {
            logger.warn("channel {} references unknown feature {} for device {}, it will be ignored",
                    channelName, featureName, getDevice().getAddress());
            return;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("{}", getChannelInfo(channelUID));
        }

        getInsteonBinding().addFeatureListener(channelUID, feature, params);
    }

    @Override
    public void channelUnlinked(ChannelUID channelUID) {
        getInsteonBinding().removeFeatureListener(channelUID);

        if (logger.isDebugEnabled()) {
            logger.debug("channel {} unlinked ", channelUID.getAsString());
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (logger.isDebugEnabled()) {
            logger.debug("channel {} was triggered with the command {}", channelUID.getAsString(), command);
        }

        getInsteonBinding().sendCommand(channelUID.getAsString(), command);
    }

    @Override
    protected void updateThing(Thing thing) {
        super.updateThing(thing);

        if (logger.isDebugEnabled()) {
            logger.debug("{}", getThingInfo());
        }

        if (isInitialized()) {
            relinkChannels();
        }
    }

    public abstract String getThingInfo();

    public List<String> getChannelIds() {
        return getThing().getChannels().stream()
                .map(channel -> channel.getUID().getId())
                .collect(Collectors.toList());
    }

    public Map<String, String> getChannelsInfo() {
        return getThing().getChannels().stream()
                .map(channel -> channel.getUID())
                .collect(Collectors.toMap(ChannelUID::getAsString,
                        channelUID -> getChannelInfo(channelUID) + " isLinked = " + isLinked(channelUID)));
    }

    private String getChannelInfo(ChannelUID channelUID) {
        Channel channel = getThing().getChannel(channelUID.getId());
        String channelName = channelUID.getAsString();
        String featureName = channel.getChannelTypeUID().getId();
        Map<String, Object> params = new HashMap<>(channel.getConfiguration().getProperties());

        StringBuilder builder = new StringBuilder(channelName);
        builder.append(" feature = ");
        builder.append(featureName);
        if (!params.isEmpty()) {
            builder.append(" parameters = ");
            builder.append(params);
        }

        return builder.toString();
    }

    protected void relinkChannels() {
        getThing().getChannels().stream()
            .map(channel -> channel.getUID())
            .filter(channelUID -> isLinked(channelUID))
            .forEach(channelUID -> channelLinked(channelUID));
    }

    public void updateThingStatus() {
        if (!getInsteonBinding().isModemDBComplete()) {
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.CONFIGURATION_PENDING,
                    "Waiting for modem database.");
        } else if (getDevice() == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Unable to determine device.");
        } else if (!getDevice().hasModemDBEntry() && !getDevice().isModem() && !getDevice().getAddress().isX10()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Device not found in modem database.");
        } else if (getDevice().isNotResponding()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Device not responding.");
        } else if (getDevice().getProductData() == null) {
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.CONFIGURATION_PENDING,
                    "Waiting for product data.");
        } else if (getDevice().getProductData().getDeviceType() == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Unsupported device.");
        } else if (getThing().getChannels().isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "No available channels.");
        } else {
            updateStatus(ThingStatus.ONLINE);
        }
    }

    // protected abstract void statusChanged(ThingStatus status);
}
