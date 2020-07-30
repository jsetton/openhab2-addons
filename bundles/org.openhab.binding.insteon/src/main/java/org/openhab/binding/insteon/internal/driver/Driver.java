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

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.io.transport.serial.SerialPortManager;
import org.openhab.binding.insteon.internal.database.ModemDB;
import org.openhab.binding.insteon.internal.device.InsteonAddress;
import org.openhab.binding.insteon.internal.device.InsteonDevice;
import org.openhab.binding.insteon.internal.message.Msg;
import org.openhab.binding.insteon.internal.message.MsgListener;

/**
 * The driver class manages the modem port.
 *
 * @author Bernd Pfrommer - Initial contribution
 * @author Rob Nielsen - Port to openHAB 2 insteon binding
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
public class Driver {
    private Port port;
    private DriverListener listener;

    public Driver(String portName, DriverListener listener, @Nullable SerialPortManager serialPortManager,
            ScheduledExecutorService scheduler) {
        this.listener = listener;
        this.port = new Port(portName, this, serialPortManager, scheduler);
    }

    public void addMsgListener(MsgListener listener) {
        port.addListener(listener);
    }

    public void removeMsgListener(MsgListener listener) {
        port.removeListener(listener);
    }

    public void start() {
        port.start();
    }

    public void stop() {
        port.stop();
    }

    public void buildLinkDB(InsteonDevice device, long delay) {
        port.buildLinkDB(device, delay);
    }

    public void writeMessage(Msg m) throws IOException {
        port.writeMessage(m);
    }

    public String getPortName() {
        return port.getName();
    }

    public InsteonAddress getModemAddress() {
        return port.getAddress();
    }

    public @Nullable InsteonDevice getModemDevice() {
        return port.getModemDevice();
    }

    public ModemDB getModemDB() {
        return port.getModemDB();
    }

    public boolean isRunning() {
        return port.isRunning();
    }

    public boolean isMsgForUs(@Nullable InsteonAddress toAddr) {
        return port.getAddress().equals(toAddr);
    }

    public void modemFound() {
        if (getModemDevice() != null) {
            listener.modemFound();
        }
    }

    public void modemDBComplete() {
        if (isModemDBComplete()) {
            listener.modemDBComplete();
        }
    }

    public void modemDBUpdated(InsteonAddress addr, int group) {
        if (isModemDBComplete()) {
            listener.modemDBUpdated(addr, group);
        }
    }

    public boolean isModemDBComplete() {
        return port.getModemDB().isComplete();
    }

    public void disconnected() {
        listener.disconnected();
    }

    public void productDataUpdated(InsteonAddress addr) {
        listener.productDataUpdated(addr);
    }

    public void requestSent(InsteonAddress addr, long time) {
        listener.requestSent(addr, time);
    }
}
