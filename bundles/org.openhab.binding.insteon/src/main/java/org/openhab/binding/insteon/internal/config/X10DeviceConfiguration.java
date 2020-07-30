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

/**
 * The {@link X10DeviceConfiguration} class contains fields mapping thing configuration parameters.
 *
 * @author Jeremy Setton - Initial contribution
 */
@NonNullByDefault
public class X10DeviceConfiguration {

    // required parameters
    private String houseCode = "";
    private int unitCode = 0;
    private String deviceType = "";

    public String getHouseCode() {
        return houseCode;
    }

    public int getUnitCode() {
        return unitCode;
    }

    public String getDeviceType() {
        return deviceType;
    }
}
