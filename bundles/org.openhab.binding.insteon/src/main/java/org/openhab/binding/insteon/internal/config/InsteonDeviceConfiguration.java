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
 * The {@link InsteonDeviceConfiguration} class contains fields mapping thing configuration parameters.
 *
 * @author Rob Nielsen - Initial contribution
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
public class InsteonDeviceConfiguration {

    // required parameters
    private String address = "";
    // optional parameters
    private @Nullable String devCat;
    private @Nullable String subCat;
    private @Nullable String productKey;

    public String getAddress() {
        return address;
    }

    public @Nullable String getDeviceCategory() {
        return devCat;
    }

    public @Nullable String getSubCategory() {
        return subCat;
    }

    public @Nullable String getProductKey() {
        return productKey;
    }
}
