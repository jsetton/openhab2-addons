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
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.insteon.internal.utils.ByteUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feature base handler class
 *
 * @author Jeremy Setton - Initial contribution
 */
@NonNullByDefault
public abstract class FeatureBaseHandler {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    protected DeviceFeature feature;
    protected Map<String, @Nullable String> parameters = new HashMap<>();

    /**
     * Constructor
     *
     * @param feature the device feature
     */
    public FeatureBaseHandler(DeviceFeature feature) {
        this.feature = feature;
    }

    /**
     * Helper function to get a parameter for the handler
     *
     * @param key name of the parameter
     * @return value of parameter using feature over handler level
     */
    private @Nullable String getParameter(String key) {
        return feature.getParameter(key, parameters.get(key));
    }

    /**
     * Helper function to get a boolean parameter for the handler
     *
     * @param key name of the parameter
     * @param def default to return if parameter not found
     * @return value of parameter (or default if not found)
     */
    protected boolean getBooleanParameter(String key, boolean def) {
        String val = getParameter(key);
        return val == null ? def : val.equals("true");
    }

    /**
     * Helper function to get a double parameter for the handler
     *
     * @param key name of the parameter
     * @param def default to return if parameter not found
     * @return value of parameter (or default if not found)
     */
    protected double getDoubleParameter(String key, double def) {
        String val = getParameter(key);
        try {
            if (val != null) {
                return Double.parseDouble(val);
            }
        } catch (NumberFormatException e) {
            logger.warn("{}: malformed double parameter in handler: {}", nm(), key);
        }
        return def;
    }

    /**
     * Helper function to get an integer parameter for the handler
     *
     * @param key name of the parameter
     * @param def default to return if parameter not found
     * @return value of parameter (or default if not found)
     */
    protected int getIntParameter(String key, int def) {
        String val = getParameter(key);
        try {
            if (val != null) {
                return ByteUtils.strToInt(val);
            }
        } catch (NumberFormatException e) {
            logger.warn("{}: malformed int parameter in handler: {}", nm(), key);
        }
        return def;
    }

    /**
     * Helper function to get a long parameter for the handler
     *
     * @param key name of the parameter
     * @param def default to return if parameter not found
     * @return value of parameter (or default if not found)
     */
    protected long getLongParameter(String key, long def) {
        String val = getParameter(key);
        try {
            if (val != null) {
                return Long.parseLong(val);
            }
        } catch (NumberFormatException e) {
            logger.warn("{}: malformed long parameter in handler: {}", nm(), key);
        }
        return def;
    }

    /**
     * Helper function to get a string parameter for the handler
     *
     * @param key name of the parameter
     * @param def default to return if parameter not found
     * @return value of parameter (or default if not found)
     */
    protected String getStringParameter(String key, String def) {
        String val = getParameter(key);
        return val == null ? def : val;
    }

    /**
     * Helper function to add parameters not defined yet for the handler
     *
     * @param map the parameter map
     */
    protected void addParameters(Map<String, @Nullable String> map) {
        for (Map.Entry<String, @Nullable String> param : map.entrySet()) {
            parameters.putIfAbsent(param.getKey(), param.getValue());
        }
    }

    /**
     * Helper function to set a parameter for the handler
     *
     * @param key the parameter key
     * @param val the parameter value
     */
    protected void setParameter(String key, @Nullable String val) {
        parameters.put(key, val);
    }

    /**
     * Shorthand to return class name for logging purposes
     *
     * @return name of the class
     */
    protected String nm() {
        return this.getClass().getSimpleName();
    }
}
