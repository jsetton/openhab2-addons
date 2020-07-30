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

import static org.openhab.binding.insteon.internal.InsteonBindingConstants.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.type.ChannelTypeUID;
import org.eclipse.smarthome.core.types.StateOption;
import org.openhab.binding.insteon.internal.InsteonStateDescriptionProvider;
import org.openhab.binding.insteon.internal.config.InsteonDeviceConfiguration;
import org.openhab.binding.insteon.internal.device.DeviceFeature;
import org.openhab.binding.insteon.internal.device.InsteonAddress;
import org.openhab.binding.insteon.internal.device.InsteonDevice;
import org.openhab.binding.insteon.internal.device.ProductData;
import org.openhab.binding.insteon.internal.device.ProductDataLoader;
import org.openhab.binding.insteon.internal.utils.StringUtils;

/**
 * The {@link InsteonDeviceHandler} is responsible for handling device commands, which are
 * sent to one of the channels.
 *
 * @author Rob Nielsen - Initial contribution
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
@SuppressWarnings("null")
public class InsteonDeviceHandler extends InsteonBaseHandler {

    private @Nullable InsteonDeviceConfiguration config;
    private @Nullable InsteonStateDescriptionProvider stateDescriptionProvider;

    public InsteonDeviceHandler(Thing thing, @Nullable InsteonStateDescriptionProvider stateDescriptionProvider) {
        super(thing);
        this.stateDescriptionProvider = stateDescriptionProvider;
    }

    @Override
    public void initialize() {
        config = getConfigAs(InsteonDeviceConfiguration.class);

        scheduler.execute(() -> {
            if (getBridge() == null) {
                String msg = "An Insteon network bridge has not been selected for this device.";
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                return;
            }

            String address = config.getAddress();
            if (!InsteonAddress.isValid(address)) {
                String msg = "Unable to start Insteon device, the address '" + address
                        + "' is invalid. It must be formatted as 'AB.CD.EF'.";
                logger.warn("{}", msg);

                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                return;
            }

            String devCat = config.getDeviceCategory();
            String subCat = config.getSubCategory();
            if (devCat != null && subCat == null || devCat == null && subCat != null) {
                String msg = "Unable to start Insteon device, either device or sub category parameter is missing.";
                logger.warn("{}", msg);

                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                return;
            }

            String productKey = config.getProductKey();
            ProductData productData = ProductDataLoader.instance().getProductData(devCat, subCat, productKey);
            if (productData != null && productData.getDeviceType() == null) {
                String msg = "Unable to start Insteon device, unsupported product '" + productData + "'.";
                logger.warn("{}", msg);

                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                return;
            }

            InsteonAddress insteonAddress = new InsteonAddress(address);
            if (getInsteonBinding().getDevice(insteonAddress) != null) {
                String msg = "An Insteon device already exists with the address '" + address + "'.";
                logger.warn("{}", msg);

                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                return;
            }

            InsteonDevice device = getInsteonBinding().makeNewDevice(this, insteonAddress, productData);
            if (device == null) {
                String msg = "Unable to initialize Insteon device '" + address + "'.";
                logger.warn("{}", msg);

                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                return;
            }

            getInsteonBinding().addDevice(insteonAddress, device);
            setDevice(device);
            initializeChannels();
            updateThingStatus();
        });
    }

    public void initializeChannels() {
        InsteonDevice device = getDevice();
        if (device == null || device.getProductData() == null || device.getProductData().getDeviceType() == null) {
            return;
        }

        String deviceType = device.getProductData().getDeviceType().getName();
        List<Channel> channels = new ArrayList<>();

        for (String featureName : device.getFeatures().keySet()) {
            DeviceFeature feature = device.getFeature(featureName);

            if (feature.isFeatureGroup()) {
                if (logger.isTraceEnabled()) {
                    logger.trace("{} is a feature group for {}. It will not be added as a channel.", featureName,
                            deviceType);
                }
            } else if (feature.isHiddenFeature()) {
                if (logger.isTraceEnabled()) {
                    logger.trace("{} is a hidden feature for {}. It will not be added as a channel.", featureName,
                            deviceType);
                }
            } else {
                // create channel using feature name as channel id
                Channel channel = createChannel(featureName, deviceType);
                if (logger.isTraceEnabled()) {
                    logger.trace("adding channel {}", channel.getUID().getAsString());
                }
                channels.add(channel);
                // add feature listener channel config if is an event feature, since not expected to be linked
                if (feature.isEventFeature()) {
                    getInsteonBinding().addFeatureListener(channel.getUID(), feature,
                            new HashMap<>(channel.getConfiguration().getProperties()));
                }
            }
        }

        updateThing(editThing().withChannels(channels).build());
    }

    private Channel createChannel(String channelId, String deviceType) {
        ChannelUID channelUID = new ChannelUID(getThing().getUID(), channelId);
        ChannelTypeUID channelTypeUID = new ChannelTypeUID(BINDING_ID, channelId);
        Channel channel = getThing().getChannel(channelUID);
        // create channel if not already available
        if (channel == null) {
            channel = getCallback().createChannelBuilder(channelUID, channelTypeUID).build();
        }
        // set channel custom settings based on product key
        setChannelCustomSettings(channelUID, deviceType);

        return channel;
    }

    private void setChannelCustomSettings(ChannelUID channelUID, String deviceType) {
        // determine key based on channel id and device type
        String key = channelUID.getId() + ":" + deviceType;
        // set channel custom state options if defined
        if (CUSTOM_STATE_DESCRIPTION_OPTIONS.containsKey(key)) {
            String[] optionList = CUSTOM_STATE_DESCRIPTION_OPTIONS.get(key);
            List<StateOption> options = new ArrayList<>();
            for (String value : optionList) {
                String label = StringUtils.capitalize(value.replaceAll("_", " ").toLowerCase());
                options.add(new StateOption(value, label));
            }

            if (logger.isTraceEnabled()) {
                logger.trace("setting state options to {} on {}", options, channelUID.getAsString());
            }
            stateDescriptionProvider.setStateOptions(channelUID, options);
        }
    }

    @Override
    public void dispose() {
        String address = config.getAddress();
        if (getBridge() != null && InsteonAddress.isValid(address)) {
            getInsteonBinding().removeDevice(new InsteonAddress(address));

            if (logger.isDebugEnabled()) {
                logger.debug("removed {} address = {}", getThing().getUID().getAsString(), address);
            }
        }

        super.dispose();
    }

    @Override
    public String getThingInfo() {
        String thingId = getThing().getUID().getAsString();
        String address = config.getAddress();
        String devCat = config.getDeviceCategory();
        String subCat = config.getSubCategory();
        String productKey = config.getProductKey();
        String channelIds = String.join(", ", getChannelIds());

        StringBuilder builder = new StringBuilder(thingId);
        builder.append(" address = ");
        builder.append(address);
        if (devCat != null) {
            builder.append(" deviceCategory = ");
            builder.append(devCat);
        }
        if (subCat != null) {
            builder.append(" subCategory = ");
            builder.append(subCat);
        }
        if (productKey != null) {
            builder.append(" productKey = ");
            builder.append(productKey);
        }
        builder.append(" channels = ");
        builder.append(channelIds);

        return builder.toString();
    }
}
