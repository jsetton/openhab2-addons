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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.insteon.internal.database.LinkDB.LinkDBRecord;
import org.openhab.binding.insteon.internal.device.InsteonDevice;
import org.openhab.binding.insteon.internal.driver.Port;
import org.openhab.binding.insteon.internal.message.FieldException;
import org.openhab.binding.insteon.internal.message.InvalidMessageTypeException;
import org.openhab.binding.insteon.internal.message.Msg;
import org.openhab.binding.insteon.internal.message.MsgListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the all-link database from incoming link record messages for a given device
 *
 * @author Jeremy Setton - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings("null")
public class LinkDBBuilder implements MsgListener {
    private static final int DOWNLOAD_TIMEOUT = 30000;

    private final Logger logger = LoggerFactory.getLogger(LinkDBBuilder.class);

    private volatile boolean done;
    private volatile int recordCount;
    private Port port;
    private ScheduledExecutorService scheduler;
    private InsteonDevice device = new InsteonDevice();
    private long delay = 0L;
    private @Nullable ScheduledFuture<?> job = null;

    public LinkDBBuilder(Port port, ScheduledExecutorService scheduler) {
        this.port = port;
        this.scheduler = scheduler;
    }

    public InsteonDevice getDevice() {
        return device;
    }

    private void setOptions(InsteonDevice device, long delay) {
        this.device = device;
        this.delay = delay;
    }

    private boolean isDone() {
        return done;
    }

    public boolean isRunning() {
        return job != null;
    }

    public void start(InsteonDevice device, long delay) {
        long startTime = System.currentTimeMillis() + delay;
        logger.debug("starting link db builder for {}", device.getAddress());
        port.addListener(this);
        setOptions(device, delay);
        startDownload();
        done = false;
        job = scheduler.scheduleWithFixedDelay(() -> {
            if (isDone()) {
                stop();
            } else if (System.currentTimeMillis() - startTime > DOWNLOAD_TIMEOUT) {
                logger.debug("link database download timeout for {}, aborting", device.getAddress());
                done();
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        logger.debug("link db builder finished for {}", device.getAddress());
        port.removeListener(this);
        job.cancel(false);
        job = null;
    }

    private void startDownload() {
        logger.trace("starting link db download for {}", device.getAddress());
        device.getLinkDB().clearRecords();
        recordCount = 0;
        getAllLinkRecords();
    }

    private void getAllLinkRecords() {
        try {
            Msg m = device.makeExtendedMessage((byte) 0x1F, (byte) 0x2F, (byte) 0x00);
            device.enqueueDelayedBlockingRequest(m, "linkDBBuilder", delay);
        } catch (InvalidMessageTypeException e) {
            logger.warn("invalid message ", e);
        } catch (FieldException e) {
            logger.warn("error parsing message ", e);
        }
    }

    @Override
    public void msg(Msg msg) {
        try {
            if (!msg.isFromAddress(device.getAddress())) {
                return;
            }
            if (msg.getByte("Cmd") == 0x51 && msg.getByte("command1") == 0x2F) {
                // check if message crc is valid based on device insteon engine checksum support
                if (device.getInsteonEngine().supportsChecksum() && !msg.hasValidCRC()) {
                    logger.debug("ignoring msg with invalid crc from {}: {}", device.getAddress(), msg);
                    return;
                }
                // add link db record
                LinkDBRecord record = LinkDBRecord.fromRecordMsg(msg);
                if (device.getLinkDB().addRecord(record)) {
                    logger.trace("got link db record #{} for {}", ++recordCount, device.getAddress());
                    // complete download if last record
                    if (record.isLast()) {
                        logger.trace("got last link db record for {}", device.getAddress());
                        done();
                    }
                }
            } else if (msg.getByte("Cmd") == 0x5C && msg.getByte("command1") == 0x2F) {
                logger.debug("got a failure reply for {}, aborting", device.getAddress());
                done();
            }
        } catch (FieldException e) {
            logger.warn("error parsing link db info reply field ", e);
        }
    }

    private void done() {
        device.getLinkDB().updateStatus();
        device.getLinkDB().logRecords();
        device.linkDBComplete(); // notify device
        done = true;
    }
}
