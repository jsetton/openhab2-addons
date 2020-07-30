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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * The {@link InsteonNetworkConfiguration} class contains fields mapping thing configuration parameters.
 *
 * @author Rob Nielsen - Initial contribution
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
public class InsteonNetworkConfiguration {

    // required parameters
    private String port = "";
    private int devicePollIntervalSeconds = 300;
    private boolean deviceDiscoveryEnabled = true;
    private boolean sceneDiscoveryEnabled = false;
    // optional parameters
    private @Nullable String additionalDeviceTypes;
    private @Nullable String additionalFeatures;
    private @Nullable String additionalProducts;

    public String getPort() {
        return port;
    }

    public boolean getDeviceDiscoveryEnabled() {
        return deviceDiscoveryEnabled;
    }

    public int getDevicePollIntervalSeconds() {
        return devicePollIntervalSeconds;
    }

    public boolean getSceneDiscoveryEnabled() {
        return sceneDiscoveryEnabled;
    }

    public @Nullable String getAdditionalDeviceTypes() {
        return additionalDeviceTypes;
    }

    public @Nullable String getAdditionalFeatures() {
        return additionalFeatures;
    }

    public @Nullable String getAdditionalProducts() {
        return additionalProducts;
    }
}
