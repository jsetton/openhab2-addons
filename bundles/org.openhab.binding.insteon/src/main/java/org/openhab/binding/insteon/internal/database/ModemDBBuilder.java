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
package org.openhab.binding.insteon.internal.database;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.insteon.internal.device.InsteonAddress;
import org.openhab.binding.insteon.internal.driver.Port;
import org.openhab.binding.insteon.internal.message.FieldException;
import org.openhab.binding.insteon.internal.message.InvalidMessageTypeException;
import org.openhab.binding.insteon.internal.message.Msg;
import org.openhab.binding.insteon.internal.message.MsgListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the modem database from incoming link record messages
 *
 * @author Bernd Pfrommer - Initial contribution
 * @author Rob Nielsen - Port to openHAB 2 insteon binding
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
@SuppressWarnings("null")
public class ModemDBBuilder implements MsgListener {
    private static final int MESSAGE_TIMEOUT = 6000;
    private static final int QUERY_TIMEOUT = 3000;

    private final Logger logger = LoggerFactory.getLogger(ModemDBBuilder.class);

    private volatile boolean done;
    private volatile long lastMsgTimestamp;
    private volatile int messageCount;
    private Port port;
    private ScheduledExecutorService scheduler;
    private @Nullable ScheduledFuture<?> job = null;
    private Map<InsteonAddress, Long> lastProductQueryTimes = new HashMap<>();

    public ModemDBBuilder(Port port, ScheduledExecutorService scheduler) {
        this.port = port;
        this.scheduler = scheduler;

        port.addListener(this);
    }

    public boolean isDone() {
        return done;
    }

    public boolean isRunning() {
        return job != null;
    }

    public void start() {
        logger.debug("starting modem db builder");
        startDownload();
        done = false;
        job = scheduler.scheduleWithFixedDelay(() -> {
            if (isDone()) {
                stop();
            } else if (System.currentTimeMillis() - lastMsgTimestamp > MESSAGE_TIMEOUT) {
                String s = "";
                if (messageCount == 0) {
                    s = " No messages were received, the PLM or hub might be broken. If this continues see "
                            + "'Known Limitations and Issues' in the Insteon binding documentation.";
                }
                logger.warn("Modem database download was unsuccessful, restarting!{}", s);
                startDownload();
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        logger.debug("modem db builder finished");
        job.cancel(false);
        job = null;
    }

    private void startDownload() {
        logger.trace("starting modem database download");
        port.getModemDB().clearEntries();
        lastMsgTimestamp = System.currentTimeMillis();
        messageCount = 0;
        getFirstLinkRecord();
    }

    private void getFirstLinkRecord() {
        getLinkRecord(true);
    }

    private void getNextLinkRecord() {
        getLinkRecord(false);
    }

    private void getLinkRecord(boolean getFirst) {
        try {
            String type = getFirst ? "GetFirstALLLinkRecord" : "GetNextALLLinkRecord";
            Msg m = Msg.makeMessage(type);
            port.writeMessage(m);
        } catch (IOException e) {
            logger.warn("error sending link record query ", e);
        } catch (InvalidMessageTypeException e) {
            logger.warn("invalid message ", e);
        }
    }

    private void getProductID(InsteonAddress addr) {
        try {
            Msg m = Msg.makeMessage("SendStandardMessage");
            m.setAddress("toAddress", addr);
            m.setByte("messageFlags", (byte) 0x0F);
            m.setByte("command1", (byte) 0x10);
            m.setByte("command2", (byte) 0x00);
            port.writeMessage(m);
        } catch (FieldException e) {
            logger.warn("cannot access field:", e);
        } catch (IOException e) {
            logger.warn("error sending product id query ", e);
        } catch (InvalidMessageTypeException e) {
            logger.warn("invalid message ", e);
        }
    }

    private boolean shouldRequestProductID(InsteonAddress addr) {
        // no request if product if already known
        if (port.getModemDB().hasProductData(addr)) {
            return false;
        }
        // no request if last product id query time within timeout window
        synchronized (lastProductQueryTimes) {
            long currentTime = System.currentTimeMillis();
            long lastQueryTime = lastProductQueryTimes.getOrDefault(addr, 0L);
            if (currentTime - lastQueryTime <= QUERY_TIMEOUT) {
                return false;
            }
            lastProductQueryTimes.put(addr, currentTime);
            return true;
        }
    }

    /**
     * processes link record messages from the modem to build database
     * and request more link records if not finished.
     * {@inheritDoc}
     */
    @Override
    public void msg(Msg msg) {
        lastMsgTimestamp = System.currentTimeMillis();
        messageCount++;
        try {
            if (msg.isPureNack()) {
                return;
            }
            if (msg.getByte("Cmd") == 0x50 && msg.isBroadcast()) {
                // we got a standard broadcast message
                handleProductData(msg);
            } else if (msg.getByte("Cmd") == 0x53) {
                // we got a link completed message
                handleLinkUpdate(msg);
            } else if (msg.getByte("Cmd") == 0x57) {
                // we got a link record response
                handleLinkRecord(msg);
            } else if (msg.getByte("Cmd") == 0x69 || msg.getByte("Cmd") == 0x6a) {
                // If the flag is "ACK/NACK", a record response
                // will follow, so we do nothing here.
                // If its "NACK", there are none
                if (msg.getByte("ACK/NACK") == 0x15) {
                    if (!isDone()) {
                        logger.debug("got all link records");
                        done();
                    }
                }
            }
        } catch (FieldException e) {
            logger.warn("error parsing modem link record field ", e);
        }
    }

    private void done() {
        port.getModemDB().setIsComplete(true);
        port.getModemDB().logEntries();
        port.getDriver().modemDBComplete(); // notify driver
        done = true;
    }

    private void handleLinkRecord(Msg msg) throws FieldException {
        InsteonAddress linkAddr = msg.getAddress("LinkAddr");
        if (shouldRequestProductID(linkAddr)) {
            getProductID(linkAddr);
        }
        if (isDone()) {
            logger.debug("modem db builder already completed, ignoring record");
            return;
        }
        port.getModemDB().addRecord(msg);
        getNextLinkRecord();
    }

    private void handleLinkUpdate(Msg msg) throws FieldException {
        InsteonAddress linkAddr = msg.getAddress("LinkAddr");
        if (shouldRequestProductID(linkAddr)) {
            getProductID(linkAddr);
        }
        int linkCode = msg.getInt("LinkCode");
        if (linkCode != 0xFF) {
            port.getModemDB().addRecord(msg);
        } else {
            port.getModemDB().deleteRecord(msg);
        }
        port.getModemDB().logEntry(linkAddr);
        port.getDriver().modemDBUpdated(linkAddr, msg.getInt("ALLLinkGroup")); // notify driver
    }

    private void handleProductData(Msg msg) throws FieldException {
        InsteonAddress fromAddr = msg.getAddress("fromAddress");
        if (!port.getModemDB().hasEntry(fromAddr) || port.getModemDB().hasProductData(fromAddr)) {
            // skip if source address not in modem db or has already product data
            return;
        }

        if (msg.getByte("command1") == 0x01 || msg.getByte("command1") == 0x02) {
            port.getModemDB().setProductData(msg);
            port.getDriver().productDataUpdated(fromAddr); // notify driver
        } else if (shouldRequestProductID(fromAddr)) {
            // request product id, for non-product data broadcast message,
            // with a 1400 ms delay to allow all-link cleanup msg to be processed beforehand,
            // and before the delayed polling (1500 ms) is triggered on an already defined battery powered device
            scheduler.schedule(() -> getProductID(fromAddr), 1400, TimeUnit.MILLISECONDS);
        }
    }
}
