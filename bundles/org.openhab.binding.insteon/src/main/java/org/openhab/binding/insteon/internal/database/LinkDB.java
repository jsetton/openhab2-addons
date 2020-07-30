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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.insteon.internal.device.InsteonAddress;
import org.openhab.binding.insteon.internal.device.InsteonDevice;
import org.openhab.binding.insteon.internal.message.FieldException;
import org.openhab.binding.insteon.internal.message.Msg;
import org.openhab.binding.insteon.internal.utils.ByteUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The LinkDB class holds all-link database records for a device
 *
 * @author Jeremy Setton - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings("null")
public class LinkDB {
    public static enum LinkDBStatus {
        EMPTY,
        COMPLETE,
        PARTIAL,
        LOADING
    }

    private static final int FIRST_RECORD_OFFSET = 0x0FFF;

    private final Logger logger = LoggerFactory.getLogger(LinkDB.class);

    private InsteonDevice device;
    private LinkDBStatus status = LinkDBStatus.EMPTY;
    private TreeMap<Integer, LinkDBRecord> records = new TreeMap<>(Collections.reverseOrder());

    public LinkDB(InsteonDevice device) {
        this.device = device;
    }

    public List<LinkDBRecord> getRecords() {
        return new ArrayList<>(records.values());
    }

    public LinkDBStatus getStatus() {
        return status;
    }

    public boolean isComplete() {
        return status == LinkDBStatus.COMPLETE;
    }

    private void setStatus(LinkDBStatus status) {
        logger.trace("setting link db status to {} for {}", status, device.getAddress());
        this.status = status;
    }

    /**
     * Adds record to database
     *
     * @param record the record to add
     * @return true if record was added
     */
    public synchronized boolean addRecord(LinkDBRecord record) {
        if (status == LinkDBStatus.EMPTY) {
            setStatus(LinkDBStatus.LOADING);
        }
        if (status != LinkDBStatus.LOADING) {
            logger.debug("incorrect link db status for {}, ignoring record", device.getAddress());
        } else if (records.containsKey(record.getOffset())) {
            logger.debug("duplicate link db record for {}, ignoring record", device.getAddress());
        } else {
            records.put(record.getOffset(), record);
            return true;
        }
        return false;
    }

    /**
     * Clears all database records
     */
    public synchronized void clearRecords() {
        logger.trace("clearing link records for {}", device.getAddress());
        records.clear();
        setStatus(LinkDBStatus.EMPTY);
    }

    /**
     * Logs all database records
     */
    public synchronized void logRecords() {
        if (logger.isDebugEnabled()) {
            if (status == LinkDBStatus.EMPTY) {
                logger.debug("no link records found for {}", device.getAddress());
            } else {
                logger.debug("---------------- start of link records for {} ----------------", device.getAddress());
                for (LinkDBRecord record : getRecords()) {
                    logger.debug("{}", record);
                }
                logger.debug("----------------- end of link records for {} -----------------", device.getAddress());
            }
        }
    }

    /**
     * Updates link database status
     */
    public synchronized void updateStatus() {
        if (records.isEmpty()) {
            setStatus(LinkDBStatus.EMPTY);
        } else {
            int firstOffset = records.firstKey();
            int lastOffset = records.lastKey();
            int expectedCount = (firstOffset - lastOffset) / 8 + 1;
            if (firstOffset != FIRST_RECORD_OFFSET) {
                logger.debug("got unexpected first record offset {} for {}", ByteUtils.getHexString(firstOffset, 4),
                        device.getAddress());
                setStatus(LinkDBStatus.PARTIAL);
            } else if (!records.lastEntry().getValue().isLast()) {
                logger.debug("got unexpected last record type for {}", device.getAddress());
                setStatus(LinkDBStatus.PARTIAL);
            } else if (expectedCount != records.size()) {
                logger.debug("got {} records for {} expected {}", records.size(), device.getAddress(), expectedCount);
                setStatus(LinkDBStatus.PARTIAL);
            } else {
                logger.debug("got complete link db records ({}) for {} ", records.size(), device.getAddress());
                setStatus(LinkDBStatus.COMPLETE);
            }
        }
    }

    /**
     * Gets a list of broadcast groups for a given component id
     *
     * @param  componentId the record data3 field
     * @return             list of the broadcast groups
     */
    public synchronized List<Integer> getBroadcastGroups(int componentId) {
        List<Integer> groups = new ArrayList<>();
        for (LinkDBRecord record : records.values()) {
            int group = record.getGroup() & 0xFF;
            // add unique group != 0 from modem responder record matching component id and on level != 0
            if (record.isResponder() && record.getComponentId() == componentId && record.getOnLevel() != 0
                    && record.getAddress().equals(device.getDriver().getModemAddress())
                    && !groups.contains(group) && group != 0) {
                groups.add(group);
            }
        }
        return groups;
    }

    /**
     * Gets a list of related devices for a given group
     *
     * @param  group the record group
     * @return       list of related device addresses
     */
    public synchronized List<InsteonAddress> getRelatedDevices(int group) {
        List<InsteonAddress> devices = new ArrayList<>();
        for (LinkDBRecord record : records.values()) {
            InsteonAddress addr = record.getAddress();
            // add unique address from controller record matching group and is in modem database
            if (record.isController() && record.getGroup() == group && !devices.contains(addr)
                    && device.getDriver().getModemDB().hasEntry(addr)) {
                devices.add(addr);
            }
        }
        return devices;
    }

    /**
     * Gets a list of responder component ids for a given controller address and group
     *
     * @param  addr  the controller address
     * @param  group the controller group
     * @return       list of responder component ids
     */
    public synchronized List<Integer> getResponderComponentIds(InsteonAddress addr, int group) {
        List<Integer> ids = new ArrayList<>();
        for (LinkDBRecord record : records.values()) {
            if (record.isResponder() && record.getGroup() == group && record.getAddress().equals(addr)) {
                ids.add(record.getComponentId() & 0xFF);
            }
        }
        return ids;
    }

    /**
     * The LinkDBRecord class holds a device link database record
     */
    @NonNullByDefault
    public static class LinkDBRecord {
        private int offset;
        private RecordType type;
        private byte group;
        private InsteonAddress address;
        private byte[] data;

        public LinkDBRecord(int offset, RecordType type, byte group, InsteonAddress address, byte[] data) {
            this.offset = offset;
            this.type = type;
            this.group = group;
            this.address = address;
            this.data = data;
        }

        public int getOffset() {
            return offset;
        }

        public boolean isController() {
            return type == RecordType.CONTROLLER;
        }

        public boolean isResponder() {
            return type == RecordType.RESPONDER;
        }

        public boolean isLast() {
            return type == RecordType.LAST;
        }

        public byte getGroup() {
            return group;
        }

        public InsteonAddress getAddress() {
            return address;
        }

        public byte[] getData() {
            return data;
        }

        public byte getOnLevel() {
            return data[0];
        }

        public byte getRampRate() {
            return data[1];
        }

        public byte getComponentId() {
            return data[2];
        }

        @Override
        public String toString() {
            return ByteUtils.getHexString(offset, 4) + " " + address + " " + type.getAcronym()
                    + " group: " + ByteUtils.getHexString(group)
                    + " data1: " + ByteUtils.getHexString(data[0])
                    + " data2: " + ByteUtils.getHexString(data[1])
                    + " data3: " + ByteUtils.getHexString(data[2]);
        }

        /**
         * Factory method for creating a LinkDBRecord from an Insteon record message
         *
         * @param  msg            the Insteon record message to parse
         * @return                the link db record
         * @throws FieldException
         */
        public static LinkDBRecord fromRecordMsg(Msg msg) throws FieldException {
            int offset = msg.getInt16("userData3");
            RecordType type = RecordType.fromRecordFlags(msg.getByte("userData6"));
            byte group = msg.getByte("userData7");
            InsteonAddress address = new InsteonAddress(msg.getBytes("userData8", 3));
            byte[] data = msg.getBytes("userData11", 3);

            return new LinkDBRecord(offset, type, group, address, data);
        }
    }
}
