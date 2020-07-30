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
package org.openhab.binding.insteon.internal.driver;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.insteon.internal.device.InsteonAddress;

/**
 * Interface for classes that want to listen to notifications from
 * the driver.
 *
 * @author Bernd Pfrommer - Initial contribution
 * @author Rob Nielsen - Port to openHAB 2 insteon binding
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
public interface DriverListener {
    /**
     * Notification that the driver was disconnected
     */
    public void disconnected();

    /**
     * Notification that the modem database is complete
     */
    public void modemDBComplete();

    /**
     * Notification that the modem database has been updated
     *
     * @param addr  the updated device address
     * @param group the updated link group
     */
    public void modemDBUpdated(InsteonAddress addr, int group);

    /**
     * Notification that the modem has been found
     */
    public void modemFound();

    /**
     * Notification that a product data has been updated
     *
     * @param addr the updated product data device address
     */
    public void productDataUpdated(InsteonAddress addr);

    /**
     * Notification that the port has sent a message request
     *
     * @param addr the device address the request was sent to
     * @param time the time the request was sent
     */
    public void requestSent(InsteonAddress addr, long time);
}
