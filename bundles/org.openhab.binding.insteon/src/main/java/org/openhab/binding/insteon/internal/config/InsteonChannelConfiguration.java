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
package org.openhab.binding.insteon.internal.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.openhab.binding.insteon.internal.device.DeviceFeature;
import org.openhab.binding.insteon.internal.device.InsteonAddress;
import org.openhab.binding.insteon.internal.utils.ByteUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * This file contains config information needed for each channel
 *
 * @author Rob Nielsen - Initial contribution
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
public class InsteonChannelConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(InsteonChannelConfiguration.class);

    private ChannelUID channelUID;
    private DeviceFeature feature;
    private Map<String, @Nullable String> parameters = new HashMap<>();
    private int broadcastGroup = -1;
    private List<InsteonAddress> relatedDevices = new ArrayList<>();

    public InsteonChannelConfiguration(ChannelUID channelUID, DeviceFeature feature, Map<String, Object> parameters) {
        this.channelUID = channelUID;
        this.feature = feature;
        setParameters(parameters);
    }

    public ChannelUID getChannelUID() {
        return channelUID;
    }

    public String getChannelName() {
        return channelUID.getAsString();
    }

    public DeviceFeature getFeature() {
        return feature;
    }

    public Map<String, @Nullable String> getParameters() {
        return parameters;
    }

    public boolean getBooleanParameter(String key, boolean def) {
        String val = parameters.get(key);
        return val == null ? def : val.equals("true");
    }

    public double getDoubleParameter(String key, double def) {
        String val = parameters.get(key);
        try {
            if (val != null) {
                return Double.parseDouble(val);
            }
        } catch (NumberFormatException e) {
            logger.warn("malformed double parameter {} for channel {}", key, getChannelName());
        }
        return def;
    }

    public int getIntParameter(String key, int def) {
        String val = parameters.get(key);
        try {
            if (val != null) {
                return ByteUtils.strToInt(val);
            }
        } catch (NumberFormatException e) {
            logger.warn("malformed int parameter {} for channel {}", key, getChannelName());
        }
        return def;
    }

    public String getStringParameter(String key, String def) {
        String val = parameters.get(key);
        return val == null ? def : val;
    }

    public boolean hasParameter(String key) {
        return parameters.containsKey(key);
    }

    public void addParameter(String key, Object val) {
        parameters.put(key, String.valueOf(val));
    }

    public void setParameters(Map<String, Object> params) {
        for (Map.Entry<String, Object> param : params.entrySet()) {
            addParameter(param.getKey(), param.getValue());
        }
    }

    public int getBroadcastGroup() {
        return broadcastGroup;
    }

    public void setBroadcastGroup(int group) {
        logger.debug("setting broadcast group to {} for {}", group, getChannelName());
        broadcastGroup = group;
    }

    public List<InsteonAddress> getRelatedDevices() {
        return relatedDevices;
    }

    public void setRelatedDevices(List<InsteonAddress> devices) {
        logger.debug("setting related devices to {} for {}", devices, getChannelName());
        relatedDevices = devices;
    }
}
