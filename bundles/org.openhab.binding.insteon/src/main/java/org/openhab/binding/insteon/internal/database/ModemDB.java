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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.insteon.internal.device.InsteonAddress;
import org.openhab.binding.insteon.internal.device.ProductData;
import org.openhab.binding.insteon.internal.device.ProductDataLoader;
import org.openhab.binding.insteon.internal.message.FieldException;
import org.openhab.binding.insteon.internal.message.Msg;
import org.openhab.binding.insteon.internal.utils.ByteUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ModemDB class holds all-link database entries for the modem
 *
 * @author Jeremy Setton - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings("null")
public class ModemDB {
    private final Logger logger = LoggerFactory.getLogger(ModemDB.class);

    private volatile boolean complete = false;
    private Map<InsteonAddress, ModemDBEntry> dbes = new ConcurrentHashMap<>();

    public List<InsteonAddress> getAddresses() {
        return new ArrayList<>(dbes.keySet());
    }

    public List<ModemDBEntry> getEntries() {
        return new ArrayList<>(dbes.values());
    }

    public @Nullable ModemDBEntry getEntry(InsteonAddress addr) {
        return dbes.get(addr);
    }

    public boolean hasEntry(InsteonAddress addr) {
        return dbes.containsKey(addr);
    }

    public @Nullable ProductData getProductData(InsteonAddress addr) {
        return hasEntry(addr) ? getEntry(addr).getProductData() : null;
    }

    public boolean hasProductData(InsteonAddress addr) {
        return getProductData(addr) != null;
    }

    public boolean isComplete() {
        return complete;
    }

    public void setIsComplete(boolean b) {
        complete = b;
    }

    /**
     * Adds record to database
     *
     * @param msg link record message to add
     */
    public void addRecord(Msg msg) {
        try {
            InsteonAddress linkAddr = msg.getAddress("LinkAddr");
            ModemDBEntry dbe = getEntry(linkAddr);
            if (dbe == null) {
                dbe = new ModemDBEntry(linkAddr);
                dbes.put(linkAddr, dbe);
            }

            ModemDBRecord dbr = ModemDBRecord.fromRecordMsg(msg);
            dbe.addRecord(dbr);
        } catch (FieldException e) {
            logger.warn("cannot access field:", e);
        }
    }

    /**
     * Deletes record from database
     *
     * @param msg link record message to delete
     */
    public void deleteRecord(Msg msg) {
        try {
            InsteonAddress linkAddr = msg.getAddress("LinkAddr");
            ModemDBEntry dbe = getEntry(linkAddr);
            if (dbe == null) {
                return;
            }

            byte group = msg.getByte("ALLLinkGroup");
            dbe.deleteRecord(group);

            if (dbe.getRecords().isEmpty()) {
                dbes.remove(linkAddr);
            }
        } catch (FieldException e) {
            logger.warn("cannot access field:", e);
        }
    }

    /**
     * Sets product data for a defined database entry
     *
     * @param msg product data message to use
     */
    public void setProductData(Msg msg) {
        try {
            InsteonAddress fromAddr = msg.getAddress("fromAddress");
            ModemDBEntry dbe = getEntry(fromAddr);
            if (dbe == null || dbe.hasProductData()) {
                return;
            }

            InsteonAddress toAddr = msg.getAddress("toAddress");
            String deviceCategory = ByteUtils.getHexString(toAddr.getHighByte());
            String subCategory = ByteUtils.getHexString(toAddr.getMiddleByte());
            int firmwareVersion = toAddr.getLowByte() & 0xFF;
            int hardwareVersion = msg.getInt("command2");

            ProductData productData = ProductDataLoader.instance().getProductData(deviceCategory, subCategory);
            productData.setFirmwareVersion(firmwareVersion);
            productData.setHardwareVersion(hardwareVersion);
            dbe.setProductData(productData);

            if (logger.isTraceEnabled()) {
                logger.trace("got product data for {} as {}", fromAddr, productData);
            }
        } catch (FieldException e) {
            logger.warn("cannot access field:", e);
        }
    }

    /**
     * Clears all database entries
     */
    public void clearEntries() {
        logger.debug("clearing modem db!");
        dbes.clear();
        complete = false;
    }

    /**
     * Logs all database entries
     */
    public void logEntries() {
        if (logger.isDebugEnabled()) {
            if (dbes.isEmpty()) {
                logger.debug("the modem database is empty");
            } else {
                logger.debug("the modem database has {} entries:", dbes.size());
                for (ModemDBEntry dbe : dbes.values()) {
                    logger.debug("{}", dbe);
                }
                if (logger.isTraceEnabled()) {
                    logger.trace("---------------- start of modem link records ----------------");
                    for (ModemDBEntry dbe : dbes.values()) {
                        for (ModemDBRecord record : dbe.getRecords()) {
                            logger.trace("{}", record);
                        }
                    }
                    logger.trace("----------------- end of modem link records -----------------");
                }
            }
        }
    }

    /**
     * Logs a database entry for a given address
     *
     * @param addr the database link address to log
     */
    public void logEntry(InsteonAddress addr) {
        if (logger.isDebugEnabled()) {
            ModemDBEntry dbe = getEntry(addr);
            if (dbe == null) {
                logger.debug("no modem database entry for {}", addr);
            } else {
                logger.debug("{}", dbe);
                if (logger.isTraceEnabled()) {
                    logger.trace("--------- start of modem link records for {} ---------", addr);
                    for (ModemDBRecord record : dbe.getRecords()) {
                        logger.trace("{}", record);
                    }
                    logger.trace("---------- end of modem link records for {} ----------", addr);
                }
            }
        }
    }

    /**
     * Gets a list of related devices for a given broadcast group
     *
     * @param  group the broadcast group
     * @return list of related device addresses
     */
    public List<InsteonAddress> getRelatedDevices(int group) {
        List<InsteonAddress> devices = new ArrayList<>();
        for (ModemDBEntry dbe : dbes.values()) {
            if (dbe.getControllerGroups().contains((byte) group)) {
                devices.add(dbe.getAddress());
            }
        }
        return devices;
    }

    /**
     * Gets a list of all broadcast groups
     *
     * @return list of all broadcast groups
     */
    public List<Integer> getBroadcastGroups() {
        List<Integer> groups = new ArrayList<>();
        for (ModemDBEntry dbe : dbes.values()) {
            for (int group : dbe.getControllerGroups()) {
                if (!groups.contains(group) && group != 0) {
                    groups.add(group);
                }
            }
        }
        return groups;
    }

    /**
     * Returns if a broadcast group is in modem database
     *
     * @param  group the broadcast group
     * @return true if the broadcast group number is in modem database
     */
    public boolean hasBroadcastGroup(int group) {
        for (ModemDBEntry dbe : dbes.values()) {
            if (dbe.getControllerGroups().contains((byte) group)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The ModemDBEntry class holds a modem device entry
     */
    @NonNullByDefault
    public class ModemDBEntry {
        private InsteonAddress address;
        private @Nullable ProductData productData = null;
        private List<ModemDBRecord> records = new ArrayList<>();
        private List<Byte> controllers = new ArrayList<>();
        private List<Byte> responders = new ArrayList<>();

        public ModemDBEntry(InsteonAddress address) {
            this.address = address;
        }

        public InsteonAddress getAddress() {
            return address;
        }

        public @Nullable ProductData getProductData() {
            return productData;
        }

        public boolean hasProductData() {
            return productData != null;
        }

        public void setProductData(ProductData productData) {
            this.productData = productData;
        }

        public List<ModemDBRecord> getRecords() {
            return records;
        }

        public void addRecord(ModemDBRecord record) {
            // add record to records list
            records.add(record);
            // add record group to controllers or responders list
            if (record.isController()) {
                controllers.add(record.getGroup());
            } else if (record.isResponder()) {
                responders.add(record.getGroup());
            }
        }

        public void deleteRecord(byte group) {
            // remove record maching group from records list
            records.removeIf(record -> record.getGroup() == group);
            // remove group from controllers list
            controllers.remove(Byte.valueOf(group));
            // remove group from responders list
            responders.remove(Byte.valueOf(group));
        }

        public List<Byte> getControllerGroups() {
            return controllers;
        }

        public List<Byte> getResponderGroups() {
            return responders;
        }

        @Override
        public String toString() {
            String s = address + ":";
            if (controllers.isEmpty()) {
                s += " modem controls no groups";
            } else {
                s += " modem controls groups (" + toGroupString(controllers) + ")";
            }
            if (responders.isEmpty()) {
                s += " and responds to no groups";
            } else {
                s += " and responds to groups (" + toGroupString(responders) + ")";
            }
            return s;
        }

        private String toGroupString(List<Byte> groups) {
            return groups.stream()
                    .map(group -> String.valueOf(group & 0xFF))
                    .sorted()
                    .collect(Collectors.joining(","));
        }
    }

    /**
     * The ModemDBRecord class holds a modem link database record
     */
    @NonNullByDefault
    public static class ModemDBRecord {
        private RecordType type;
        private byte group;
        private InsteonAddress address;
        private byte[] data;

        public ModemDBRecord(RecordType type, byte group, InsteonAddress address, byte[] data) {
            this.type = type;
            this.group = group;
            this.address = address;
            this.data = data;
        }

        public boolean isController() {
            return type == RecordType.CONTROLLER;
        }

        public boolean isResponder() {
            return type == RecordType.RESPONDER;
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

        public byte getData1() {
            return data[0];
        }

        public byte getData2() {
            return data[1];
        }

        public byte getData3() {
            return data[2];
        }

        @Override
        public String toString() {
            return address + " " + type.getAcronym()
                    + " group: " + ByteUtils.getHexString(group)
                    + " data1: " + ByteUtils.getHexString(data[0])
                    + " data2: " + ByteUtils.getHexString(data[1])
                    + " data3: " + ByteUtils.getHexString(data[2]);
        }

        /**
         * Factory method for creating a ModemDBRecord from an Insteon record message
         *
         * @param  msg            the Insteon record message to parse
         * @return                the modem db record
         * @throws FieldException
         */
        public static ModemDBRecord fromRecordMsg(Msg msg) throws FieldException {
            RecordType type = RecordType.fromRecordMsg(msg);
            byte group = msg.getByte("ALLLinkGroup");
            InsteonAddress address = msg.getAddress("LinkAddr");
            byte[] data = !msg.containsField("RecordFlags") ? new byte[3] : new byte[] {
                msg.getByte("LinkData1"), msg.getByte("LinkData2"), msg.getByte("LinkData3") };

            return new ModemDBRecord(type, group, address, data);
        }
    }
}
