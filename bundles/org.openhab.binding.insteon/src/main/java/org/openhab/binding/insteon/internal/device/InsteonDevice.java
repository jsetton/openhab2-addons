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

import static org.openhab.binding.insteon.internal.InsteonBindingConstants.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.function.Predicate;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.types.State;
import org.openhab.binding.insteon.internal.database.LinkDB;
import org.openhab.binding.insteon.internal.database.ModemDB.ModemDBEntry;
import org.openhab.binding.insteon.internal.device.DeviceType.FeatureEntry;
import org.openhab.binding.insteon.internal.device.DeviceType.FeatureGroup;
import org.openhab.binding.insteon.internal.device.GroupMessageStateMachine.GroupMessage;
import org.openhab.binding.insteon.internal.driver.Driver;
import org.openhab.binding.insteon.internal.driver.Poller;
import org.openhab.binding.insteon.internal.handler.InsteonDeviceHandler;
import org.openhab.binding.insteon.internal.message.FieldException;
import org.openhab.binding.insteon.internal.message.InvalidMessageTypeException;
import org.openhab.binding.insteon.internal.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The InsteonDevice class holds known per-device state of a single Insteon device,
 * including the address, what port(modem) to reach it on etc.
 * Note that some Insteon devices de facto consist of two devices (let's say
 * a relay and a sensor), but operate under the same address. Such devices will
 * be represented just by a single InsteonDevice. Their different personalities
 * will then be represented by DeviceFeatures.
 *
 * @author Bernd Pfrommer - Initial contribution
 * @author Rob Nielsen - Port to openHAB 2 insteon binding
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
@SuppressWarnings("null")
public class InsteonDevice {
    private static final int BCAST_STATE_TIMEOUT = 2000; // in milliseconds
    private static final int FAILED_MSG_COUNT_THRESHOLD = 5;

    private static final Logger logger = LoggerFactory.getLogger(InsteonDevice.class);

    public enum DeviceStatus {
        INITIALIZED,
        POLLING
    }

    private InsteonAddress address = new InsteonAddress();
    private long pollInterval = -1L; // in milliseconds
    private @Nullable Driver driver = null;
    private @Nullable InsteonDeviceHandler handler = null;
    private Map<String, DeviceFeature> features = new HashMap<>();
    private Map<String, Boolean> flags = new HashMap<>();
    private @Nullable ProductData productData = null;
    private InsteonEngine engine = InsteonEngine.UNKNOWN;
    private volatile int failedMsgCount = 0;
    private volatile long lastTimePolled = 0L;
    private volatile long lastMsgReceived = 0L;
    private Queue<Msg> messageQueue = new LinkedList<>();
    private PriorityQueue<RQEntry> deferredQueue = new PriorityQueue<>();
    private Map<String, RQEntry> deferredQueueHash = new HashMap<>();
    private PriorityQueue<RQEntry> requestQueue = new PriorityQueue<>();
    private Map<String, RQEntry> requestQueueHash = new HashMap<>();
    private @Nullable DeviceFeature featureQueried = null;
    private long lastQueryTime = 0L;
    private LinkDB linkDB;
    private DeviceStatus status = DeviceStatus.INITIALIZED;
    private Map<Byte, Long> lastBroadcastReceived = new HashMap<>();
    private Map<Integer, @Nullable GroupMessageStateMachine> groupState = new HashMap<>();

    /**
     * Constructor
     */
    public InsteonDevice() {
        lastMsgReceived = System.currentTimeMillis();
        linkDB = new LinkDB(this);
    }

    // --------------------- simple getters -----------------------------

    public @Nullable ProductData getProductData() {
        return productData;
    }

    public Map<String, Boolean> getFlags() {
        return flags;
    }

    public boolean getFlag(String key, boolean def) {
        synchronized (flags) {
            return flags.getOrDefault(key, def);
        }
    }

    public boolean hasModemDBEntry() {
        return getFlag("modemDBEntry", false);
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public InsteonAddress getAddress() {
        return (address);
    }

    public @Nullable Driver getDriver() {
        return driver;
    }

    public @Nullable InsteonDeviceHandler getHandler() {
        return handler;
    }

    public InsteonEngine getInsteonEngine() {
        return engine;
    }

    public long getPollInterval() {
        return pollInterval;
    }

    public boolean isBatteryPowered() {
        return getFlag("batteryPowered", false);
    }

    public boolean isModem() {
        return getFlag("modem", false);
    }

    public @Nullable DeviceFeature getFeature(String name) {
        return features.get(name);
    }

    public Map<String, DeviceFeature> getFeatures() {
        return features;
    }

    public double getDoubleLastMsgValue(String name, double def) {
        DeviceFeature f = getFeature(name);
        return f == null ? def : f.getDoubleLastMsgValue(def);
    }

    public int getIntLastMsgValue(String name, int def) {
        DeviceFeature f = getFeature(name);
        return f == null ? def : f.getIntLastMsgValue(def);
    }

    public @Nullable State getLastState(String name) {
        DeviceFeature f = getFeature(name);
        return f == null ? null : f.getLastState();
    }

    public LinkDB getLinkDB() {
        return linkDB;
    }

    public byte getX10HouseCode() {
        return (address.getX10HouseCode());
    }

    public byte getX10UnitCode() {
        return (address.getX10UnitCode());
    }

    public boolean isNotResponding() {
        return failedMsgCount >= FAILED_MSG_COUNT_THRESHOLD;
    }

    public boolean hasValidPollingInterval() {
        return (pollInterval > 0);
    }

    public long getPollOverDueTime() {
        return (lastTimePolled - lastMsgReceived);
    }

    public @Nullable DeviceFeature getFeatureQueried() {
        synchronized (requestQueue) {
            return (featureQueried);
        }
    }

    // --------------------- simple setters -----------------------------

    public void setStatus(DeviceStatus aI) {
        status = aI;
    }

    public void setHasModemDBEntry(boolean b) {
        setFlag("modemDBEntry", b);
    }

    public void setAddress(InsteonAddress ia) {
        address = ia;
    }

    public void setDriver(Driver d) {
        driver = d;
    }

    public void setHandler(InsteonDeviceHandler h) {
        handler = h;
    }

    public void setInsteonEngine(InsteonEngine ie) {
        if (logger.isTraceEnabled()) {
            logger.trace("setting insteon engine for {} to {}", address, ie);
        }
        engine = ie;
    }

    public void setIsModem(boolean b) {
        setFlag("modem", b);
    }

    public void setProductData(ProductData pd) {
        if (logger.isTraceEnabled()) {
            logger.trace("setting product data for {} to {}", address, pd);
        }
        productData = pd;
    }

    public void setFlag(String key, boolean b) {
        if (logger.isTraceEnabled()) {
            logger.trace("setting {} flag for {} to {}", key, address, b);
        }
        synchronized (flags) {
            flags.put(key, b);
        }
    }

    public void setFlags(Map<String, Boolean> flags) {
        for (Map.Entry<String, Boolean> flag : flags.entrySet()) {
            setFlag(flag.getKey(), flag.getValue());
        }
    }

    public void setPollInterval(long pi) {
        if (logger.isTraceEnabled()) {
            logger.trace("setting poll interval for {} to {}", address, pi);
        }
        if (pi > 0) {
            pollInterval = pi;
        }
    }

    public void setFeatureQueried(@Nullable DeviceFeature f) {
        synchronized (requestQueue) {
            featureQueried = f;
        }
    }

    /**
     * Handles link db builder complete notifications
     */
    public void linkDBComplete() {
        // resume request queue
        RequestQueueManager.instance().resume();
        // update channel configs if link db is complete
        if (linkDB.isComplete()) {
            updateChannelConfigs();
        }
    }

    /**
     * Returns if this device is awake
     *
     * @return true if device not battery powered or within awake time
     */
    public boolean isAwake() {
        if (isBatteryPowered()) {
            // define awake time based on last stay awake feature state (ON => 4 minutes; OFF => 3 seconds)
            State state = getLastState(STAY_AWAKE_FEATURE);
            int awakeTime = state == OnOffType.ON ? 240000 : 3000; // in msec
            if (System.currentTimeMillis() - lastMsgReceived > awakeTime) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns if a received message is a failure report
     *
     * @param  msg received message to check
     * @return     true if message cmd is 0x5C
     */
    public boolean isFailureReportMsg(Msg msg) {
        boolean isFailureReportMsg = false;
        try {
            if (msg.getByte("Cmd") == 0x5C) {
                if (logger.isDebugEnabled()) {
                    logger.debug("got a failure report msg: {}", msg);
                }
                failedMsgCount++;
                isFailureReportMsg = true;
            } else {
                failedMsgCount = 0;
            }
        } catch (FieldException e) {
            logger.warn("error parsing msg {}: ", msg, e);
        }

        if (handler != null) {
            handler.updateThingStatus();
        }
        return isFailureReportMsg;
    }

    /**
     * Returns if device is pollable
     *
     * @return true if device is pollable
     */
    public boolean isPollable() {
        if (!features.isEmpty() && !isBatteryPowered()) {
            // pollable if feature list not empty and not battery powered
            return true;
        }
        return false;
    }

    /**
     * Start polling this device
     */
    public void startPolling() {
        // start polling if currently disabled
        if (getStatus() != DeviceStatus.POLLING) {
            int ndbes = driver.getModemDB().getEntries().size();
            Poller.instance().startPolling(this, ndbes);
            setStatus(DeviceStatus.POLLING);
        }
    }

    /**
     * Stop polling this device
     */
    public void stopPolling() {
        // stop polling if currently enabled
        if (getStatus() == DeviceStatus.POLLING) {
            Poller.instance().stopPolling(this);
            setStatus(DeviceStatus.INITIALIZED);
        }
    }

    /**
     * Execute the polling of this device
     *
     * @param delay scheduling delay (in milliseconds)
     */
    public void doPoll(long delay) {
        // schedule polling of insteon engine feature if unknown
        if (engine == InsteonEngine.UNKNOWN) {
            doPollFeature(INSTEON_ENGINE_FEATURE, delay);
            return; // insteon engine needs to be known before enqueueing more messages
        }
        // process deferred queue if not empty
        if (!deferredQueue.isEmpty()) {
            processDeferredQueue(delay);
        }
        // build this device link db if not complete
        if (!linkDB.isComplete()) {
            driver.buildLinkDB(this, delay);
        }
        // schedule polling of all features
        schedulePoll(delay, f -> true);
    }

    /**
     * Execute the polling for a specific feature name
     *
     * @param  name  feature name to poll
     * @param  delay scheduling delay (in milliseconds)
     */
    public void doPollFeature(String name, long delay) {
        DeviceFeature f = getFeature(name);
        if (f != null) {
            Msg m = f.makePollMsg();
            if (m != null) {
                enqueueDelayedRequest(m, f, delay);
            }
        }
    }

    /**
     * Execute the partial polling of this device
     * limiting to responder features only
     *
     * @param delay scheduling delay (in milliseconds)
     */
    public void doPollResponders(long delay) {
        schedulePoll(delay, f -> f.hasResponderFeatures());
    }

    /**
     * Execute poll on this device: create an array of messages,
     * add them to the request queue, and schedule the queue
     * for processing.
     *
     * @param delay         scheduling delay (in milliseconds)
     * @param featureFilter feature filter to apply
     */
    private void schedulePoll(long delay, Predicate<DeviceFeature> featureFilter) {
        long spacing = 0;
        synchronized (features) {
            for (DeviceFeature f : features.values()) {
                // skip if is event feature or feature filter doesn't match
                if (f.isEventFeature() || !featureFilter.test(f)) {
                    continue;
                }
                // poll feature with linked channel listeners or never queried before
                if (f.hasListeners() || f.getQueryStatus() == DeviceFeature.QueryStatus.NEVER_QUERIED) {
                    Msg m = f.makePollMsg();
                    if (m != null) {
                        enqueueDelayedRequest(m, f, delay + spacing);
                        spacing += m.getQuietTime();
                    }
                }
            }
        }
        // update last time polled if at least one poll message enqueued
        if (spacing > 0) {
            lastTimePolled = System.currentTimeMillis();
        }
    }

    /**
     * Trigger the update of the channel configs for controller feature
     */
    private void updateChannelConfigs() {
        synchronized (features) {
            for (DeviceFeature f : features.values()) {
                if (f.isControllerFeature()) {
                    f.updateChannelConfigs();
                }
            }
        }
    }

    public List<DeviceFeature> getRelatedFeatures(InsteonAddress addr, int group) {
        List<DeviceFeature> relatedFeatures = new ArrayList<>();
        // synchronized (features) {
        //     for (int componentId : linkDB.getResponderComponentIds(addr, group)) {
        //         for (DeviceFeature f : features.values()) {
        //             //if (f.isActuatorFeature() && )
        //         }
        //     }
        // }
        return relatedFeatures;
    }

    /**
     * Handle incoming message for this device by forwarding
     * it to all features that this device supports
     *
     * @param msg the incoming message
     */
    public void handleMessage(Msg msg) {
        lastMsgReceived = System.currentTimeMillis();
        // determine if message is a failure report (0x5C)
        boolean isFailureReportMsg = isFailureReportMsg(msg);
        // store message if not failure report and this device is unknown (no features defined)
        if (!isFailureReportMsg && features.isEmpty()) {
            if (logger.isTraceEnabled()) {
                logger.trace("storing message for unknown device {}", address);
            }
            synchronized (messageQueue) {
                messageQueue.add(msg);
            }
            return;
        }
        // iterate over device features
        synchronized (features) {
            // first update all features that are
            // not status features
            for (DeviceFeature f : features.values()) {
                if (!f.isStatusFeature()) {
                    if (!isFailureReportMsg) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("----- applying message to feature: {}", f.getName());
                        }
                        if (f.handleMessage(msg)) {
                            // handled a reply to a query,
                            // mark it as answered and processed
                            if (logger.isTraceEnabled()) {
                                logger.trace("handled reply of direct: {}", f.getName());
                            }
                            f.setQueryStatus(DeviceFeature.QueryStatus.QUERY_ANSWERED);
                            setFeatureQueried(null);
                            break;
                        }
                    } else {
                        if (f.isMyDirectAckOrNack(msg)) {
                            // received a failed report reply to a query,
                            // re-initialize its status
                            f.initializeQueryStatus();
                            setFeatureQueried(null);
                            break;
                        }
                    }
                }
            }
            // then update all the status features,
            // e.g. when the device was last updated
            for (DeviceFeature f : features.values()) {
                if (f.isStatusFeature()) {
                    if (!isFailureReportMsg) {
                        f.handleMessage(msg);
                    }
                }
            }
        }
    }

    /**
     * Helper method to make standard message
     *
     * @param flags
     * @param cmd1
     * @param cmd2
     * @return standard message
     * @throws FieldException
     * @throws IOException
     */
    public Msg makeStandardMessage(byte flags, byte cmd1, byte cmd2)
            throws FieldException, InvalidMessageTypeException {
        return (makeStandardMessage(flags, cmd1, cmd2, -1));
    }

    /**
     * Helper method to make standard message, possibly with group
     *
     * @param flags
     * @param cmd1
     * @param cmd2
     * @param group (-1 if not a group message)
     * @return standard message
     * @throws FieldException
     * @throws IOException
     */
    public Msg makeStandardMessage(byte flags, byte cmd1, byte cmd2, int group)
            throws FieldException, InvalidMessageTypeException {
        Msg m = Msg.makeMessage("SendStandardMessage");
        InsteonAddress addr = null;
        byte f = flags;
        if (group != -1) {
            f |= 0xc0; // mark message as group message
            // and stash the group number into the address
            addr = new InsteonAddress((byte) 0, (byte) 0, (byte) (group & 0xff));
        } else {
            addr = getAddress();
        }
        m.setAddress("toAddress", addr);
        m.setByte("messageFlags", f);
        m.setByte("command1", cmd1);
        m.setByte("command2", cmd2);
        // set default quiet time accounting for ack response on non-broadcast messages
        m.setQuietTime(m.isBroadcast() ? 0L : 1000L);
        return m;
    }

    public Msg makeX10Message(byte rawX10, byte X10Flag) throws FieldException, InvalidMessageTypeException {
        Msg m = Msg.makeMessage("SendX10Message");
        m.setByte("rawX10", rawX10);
        m.setByte("X10Flag", X10Flag);
        m.setQuietTime(300L);
        return m;
    }

    /**
     * Helper method to make extended message
     *
     * @param flags
     * @param cmd1
     * @param cmd2
     * @return extended message
     * @throws FieldException
     * @throws IOException
     */
    public Msg makeExtendedMessage(byte flags, byte cmd1, byte cmd2)
            throws FieldException, InvalidMessageTypeException {
        return makeExtendedMessage(flags, cmd1, cmd2, new byte[] {});
    }

    /**
     * Helper method to make extended message
     *
     * @param flags
     * @param cmd1
     * @param cmd2
     * @param data array with userdata
     * @return extended message
     * @throws FieldException
     * @throws IOException
     */
    public Msg makeExtendedMessage(byte flags, byte cmd1, byte cmd2, byte[] data)
            throws FieldException, InvalidMessageTypeException {
        Msg m = Msg.makeMessage("SendExtendedMessage");
        m.setAddress("toAddress", getAddress());
        m.setByte("messageFlags", (byte) (((flags & 0xff) | 0x10) & 0xff));
        m.setByte("command1", cmd1);
        m.setByte("command2", cmd2);
        m.setUserData(data);
        // set crc only if device insteon engine supports checksum
        if (engine.supportsChecksum()) {
            m.setCRC();
        }
        // set default quiet time accounting for ack followed by direct response messages
        m.setQuietTime(2000L);
        return m;
    }

    /**
     * Helper method to make extended message, but with different CRC calculation
     *
     * @param flags
     * @param cmd1
     * @param cmd2
     * @param data array with user data
     * @return extended message
     * @throws FieldException
     * @throws IOException
     */
    public Msg makeExtendedMessageCRC2(byte flags, byte cmd1, byte cmd2, byte[] data)
            throws FieldException, InvalidMessageTypeException {
        Msg m = Msg.makeMessage("SendExtendedMessage");
        m.setAddress("toAddress", getAddress());
        m.setByte("messageFlags", (byte) (((flags & 0xff) | 0x10) & 0xff));
        m.setByte("command1", cmd1);
        m.setByte("command2", cmd2);
        m.setUserData(data);
        m.setCRC2();
        // set default quiet time accounting for ack followed by direct response messages
        m.setQuietTime(2000L);
        return m;
    }

    /**
     * Processes stored messages
     */
    public void processMessageQueue() {
        synchronized (messageQueue) {
            while (!messageQueue.isEmpty()) {
                Msg msg = messageQueue.poll();
                if (logger.isTraceEnabled()) {
                    logger.trace("replaying stored msg: {}", msg);
                }
                msg.setIsReplayed(true);
                handleMessage(msg);
            }
        }
    }

    /**
     * Processes deferred request entries
     *
     * @param delay time (in milliseconds) to delay before sending message
     */
    public void processDeferredQueue(long delay) {
        synchronized (deferredQueue) {
            while (!deferredQueue.isEmpty()) {
                RQEntry rqe = deferredQueue.poll();
                deferredQueueHash.remove(rqe.getName());
                rqe.setExpirationTime(delay);
                if (logger.isTraceEnabled()) {
                    logger.trace("enqueuing deferred request: {}", rqe.getName());
                }
                addRQEntry(rqe);
            }
        }
    }

    /**
     * Called by the RequestQueueManager when the queue has expired
     *
     * @param timeNow
     * @return time when to schedule the next message (timeNow + quietTime)
     */
    public long processRequestQueue(long timeNow) {
        synchronized (requestQueue) {
            if (requestQueue.isEmpty()) {
                return 0L;
            }
            // check if a feature queried is in progress
            if (featureQueried != null) {
                switch (featureQueried.getQueryStatus()) {
                    case QUERY_QUEUED:
                        // wait for feature queried request to be sent
                        if (logger.isDebugEnabled()) {
                            logger.debug("still waiting for {} query to be sent to {}", featureQueried.getName(),
                                    address);
                        }
                        return timeNow + 1000L; // retry in 1000 ms
                    case QUERY_PENDING:
                        // wait for the feature queried response to be processed
                        long dt = timeNow - (lastQueryTime + featureQueried.getDirectAckTimeout());
                        if (dt < 0) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("still waiting for {} query reply from {} for another {} msec",
                                        featureQueried.getName(), address, -dt);
                            }
                            return timeNow + 1000L; // retry in 1000 ms
                        }

                        if (logger.isDebugEnabled()) {
                            logger.debug("gave up waiting for {} query reply from {}", featureQueried.getName(),
                                    address);
                        }
                        // mark feature queried as expired
                        featureQueried.setQueryStatus(DeviceFeature.QueryStatus.QUERY_EXPIRED);
                        break;
                    case QUERY_ANSWERED:
                        // do nothing, just handle race condition since feature queried was already answered
                        break;
                    default:
                        if (logger.isDebugEnabled()) {
                            logger.debug("unexpected feature {} queried status: {}", featureQueried.getName(),
                                    featureQueried.getQueryStatus());
                        }
                }
                // reset feature queried
                featureQueried = null;
            }
            // take the next request off the queue
            RQEntry rqe = requestQueue.poll();
            // remove it from the request queue hash
            requestQueueHash.remove(rqe.getName());
            // set feature queried for non-broadcast request message
            if (!rqe.getMsg().isBroadcast()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("request taken off direct: {} {}", rqe.getName(), rqe.getMsg());
                }
                if (rqe.getFeature() != null) {
                    // set feature queried if defined
                    featureQueried = rqe.getFeature();
                    // mark its status as queued
                    featureQueried.setQueryStatus(DeviceFeature.QueryStatus.QUERY_QUEUED);
                }
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debug("request taken off bcast: {} {}", rqe.getName(), rqe.getMsg());
                }
            }
            // pause request queue if blocking entry
            if (rqe.isBlocking()) {
                RequestQueueManager.instance().pause();
            }
            try {
                writeMessage(rqe.getMsg());
            } catch (IOException e) {
                logger.warn("message write failed for msg {}", rqe.getMsg(), e);
            }
            // figure out when the request queue should be checked next
            RQEntry rqenext = requestQueue.peek();
            long quietTime = rqe.getMsg().getQuietTime();
            long nextExpTime = (rqenext == null ? 0L : rqenext.getExpirationTime());
            long nextTime = Math.max(timeNow + quietTime, nextExpTime);
            if (logger.isDebugEnabled()) {
                logger.debug("next request queue processed in {} msec, quiettime = {}", nextTime - timeNow, quietTime);
            }
            return (nextTime);
        }
    }

    /**
     * Handles message request sent events for this device
     *
     * @param timeNow the time the request was sent
     */
    public void handleRequestSent(long timeNow) {
        if (featureQueried != null) {
            // set last query time
            lastQueryTime = timeNow;
            // mark feature queried as pending
            featureQueried.setQueryStatus(DeviceFeature.QueryStatus.QUERY_PENDING);
        }
    }

    /**
     * Enqueues request to be sent at the next possible time (with associated feature)
     *
     * @param m request message to be sent
     * @param f device feature that sent this message
     */
    public void enqueueRequest(Msg m, DeviceFeature f) {
        enqueueDelayedRequest(m, f, 0);
    }

    /**
     * Enqueues request to be sent at the next possible time (no associated feature)
     *
     * @param m    request message to be sent
     * @param name request name to use
     */
    public void enqueueRequest(Msg m, String name) {
        enqueueDelayedRequest(m, name, 0);
    }

    /**
     * Enqueues blocking request to be sent at the next possible time (no associated feature)
     *
     * @param m    request message to be sent
     * @param name request name to use
     */
    public void enqueueBlockingRequest(Msg m, String name) {
        enqueueDelayedBlockingRequest(m, name, 0);
    }

    /**
     * Enqueues request to be sent after a delay (with associated feature)
     *
     * @param m     request message to be sent
     * @param f     device feature that sent this message
     * @param delay time (in milliseconds) to delay before sending request
     */
    public void enqueueDelayedRequest(Msg m, DeviceFeature f, long delay) {
        RQEntry rqe = new RQEntry(f, m, delay, false);
        f.setQueryStatus(DeviceFeature.QueryStatus.QUERY_CREATED);
        addRQEntry(rqe);
    }

    /**
     * Enqueues request to be sent after a delay (no associated feature)
     *
     * @param m     request message to be sent
     * @param name  request name to use
     * @param delay time (in milliseconds) to delay before sending request
     */
    public void enqueueDelayedRequest(Msg m, String name, long delay) {
        RQEntry rqe = new RQEntry(name, m, delay, false);
        addRQEntry(rqe);
    }

    /**
     * Enqueues blocking request to be sent after a delay (no associated feature)
     *
     * @param m     request message to be sent
     * @param name  request name to use
     * @param delay time (in milliseconds) to delay before sending request
     */
    public void enqueueDelayedBlockingRequest(Msg m, String name, long delay) {
        RQEntry rqe = new RQEntry(name, m, delay, true);
        addRQEntry(rqe);
    }

    /**
     * Adds request queue entry
     *
     * @param rqe request queue entry to add
     */
    private void addRQEntry(RQEntry rqe) {
        if (!isAwake()) {
            if (logger.isTraceEnabled()) {
                logger.trace("deferring request for sleeping device {}", address);
            }
            synchronized (deferredQueue) {
                String name = rqe.getName();
                RQEntry qe = deferredQueueHash.get(name);
                if (qe != null) {
                    if (logger.isTraceEnabled()) {
                        logger.trace("overwriting existing deferred request {} for {}", name, address);
                    }
                    deferredQueue.remove(qe);
                    deferredQueueHash.remove(name);
                }
                deferredQueue.add(rqe);
                deferredQueueHash.put(name, rqe);
            }
        } else {
            long delay = rqe.getExpirationTime() - System.currentTimeMillis();
            if (logger.isTraceEnabled()) {
                logger.trace("enqueuing request with delay {} msec, blocking: {}", delay, rqe.isBlocking());
            }
            synchronized (requestQueue) {
                String name = rqe.getName();
                RQEntry qe = requestQueueHash.get(name);
                if (qe != null) {
                    if (logger.isTraceEnabled()) {
                        logger.trace("overwriting existing request {} for {}", name, address);
                    }
                    requestQueue.remove(qe);
                    requestQueueHash.remove(name);
                }
                requestQueue.add(rqe);
                requestQueueHash.put(name, rqe);
            }
            // set request as urgent if this device is battery powered
            boolean urgent = isBatteryPowered();
            RequestQueueManager.instance().addQueue(this, delay, urgent);
        }
    }

    /**
     * Clears request queues
     */
    private void clearRequestQueues() {
        if (logger.isTraceEnabled()) {
            logger.trace("clearing request queues for {}", address);
        }
        synchronized (deferredQueue) {
            deferredQueue.clear();
            deferredQueueHash.clear();
        }
        synchronized (requestQueue) {
            requestQueue.clear();
            requestQueueHash.clear();
        }
    }

    /**
     * Sends a write message request to driver
     * @param  m           message to be written
     * @throws IOException
     */
    private void writeMessage(Msg m) throws IOException {
        driver.writeMessage(m);
    }

    /**
     * Instantiates features based on a device type
     *
     * @param dt device type to instantiate features from
     */
    private void instantiateFeatures(DeviceType dt) {
        for (FeatureEntry fe : dt.getFeatures()) {
            DeviceFeature f = DeviceFeature.makeDeviceFeature(fe.getType());
            if (f == null) {
                logger.warn("device type {} references unknown feature type: {}", dt, fe.getType());
            } else {
                addFeature(f, fe.getName(), fe.getParameters());
            }
        }
        for (FeatureGroup fg : dt.getFeatureGroups()) {
            DeviceFeature f = DeviceFeature.makeDeviceFeature(fg.getType());
            if (f == null) {
                logger.warn("device type {} references unknown feature group type: {}", dt, fg.getType());
            } else {
                addFeature(f, fg.getName(), fg.getParameters());
                connectFeatures(f, fg.getConnectedFeatures());
            }
        }
    }

    /**
     * Connects group features to its parent
     *
     * @param gf       group feature to connect to
     * @param features connected features part of that group feature
     */
    private void connectFeatures(DeviceFeature gf, List<String> features) {
        for (String name : features) {
            DeviceFeature f = getFeature(name);
            if (f == null) {
                logger.warn("feature group {} references unknown feature {}", gf.getName(), name);
            } else {
                if (logger.isTraceEnabled()) {
                    logger.trace("{} connected feature: {}", gf.getName(), f.getName());
                }
                f.addParameters(gf.getParameters());
                f.setGroupFeature(gf);
                f.setPollHandler(null);
                gf.addConnectedFeature(f);
            }
        }
    }

    /**
     * Adds feature to this device
     *
     * @param f      feature object to add
     * @param name   feature name
     * @param params feature parameters
     */
    private void addFeature(DeviceFeature f, String name, Map<String, @Nullable String> params) {
        f.setDevice(this);
        f.setName(name);
        f.addParameters(params);
        f.initializeQueryStatus();
        synchronized (features) {
            features.put(name, f);
        }
    }

    /**
     * Clears all features and request queues for this device
     */
    private void clearFeatures() {
        if (logger.isTraceEnabled()) {
            logger.trace("clearing features for {}", address);
        }
        synchronized (features) {
            features.clear();
        }
    }

    /**
     * Resets this device features
     *
     */
    public void resetFeatures() {
        DeviceType dt = productData.getDeviceType();
        if (dt == null) {
            logger.debug("unsupported product for {} with data {}", address, productData);
            return;
        }

        // clear features if not empty
        if (!features.isEmpty()) {
            clearFeatures();
        }
        // clear request queues if either one not empty
        if (!requestQueue.isEmpty() || !deferredQueue.isEmpty()) {
            clearRequestQueues();
        }
        // instantiate features based on device type
        instantiateFeatures(dt);
        // update flags
        setFlags(dt.getFlags());
        // reinitialize handler channels
        handler.initializeChannels();
        // process message queue
        processMessageQueue();
    }

    /**
     * Updates this device product data
     *
     * @param newData the new product data to use
     */
    public void updateProductData(ProductData newData) {
        // update device type if product data undefined
        if (productData == null) {
            // set new product data
            setProductData(newData);
            // reset this device features
            resetFeatures();
            return;
        }

        // log message if any product id or device type discrepancies
        if (!productData.isSameProductID(newData)) {
            if (productData.isSameDeviceType(newData)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("configured product devCat:{} subCat:{} for {} differ with polled data {}",
                            productData.getDeviceCategory(), productData.getSubCategory(), address, newData);
                }
            } else {
                logger.warn("configured product {} for {} differ with polled data {}",
                        productData, address, newData);
            }
        }

        // update product data
        productData.update(newData);

        if (logger.isDebugEnabled()) {
            logger.debug("updated product data for {} to {}", address, productData);
        }
    }

    /**
     * Get the state of the state machine that suppresses duplicates for broadcast messages.
     *
     * @param cmd1 the cmd1 from the broadcast message received
     * @return true if the broadcast message is NOT a duplicate
     */
    public boolean getBroadcastState(byte cmd1) {
        synchronized (lastBroadcastReceived) {
            long timeLapse = lastMsgReceived - lastBroadcastReceived.getOrDefault(cmd1, lastMsgReceived);
            if (timeLapse > 0 && timeLapse < BCAST_STATE_TIMEOUT) {
                return false;
            } else {
                lastBroadcastReceived.put(cmd1, lastMsgReceived);
                return true;
            }
        }
    }

    /**
     * Get the state of the state machine that suppresses duplicates for group messages.
     * The state machine is advance the first time it is called for a message,
     * otherwise return the current state.
     *
     * @param group the insteon group of the broadcast message
     * @param a the type of group message came in (action etc)
     * @param cmd1 cmd1 from the message received
     * @return true if the group message is NOT a duplicate
     */
    public boolean getGroupState(int group, GroupMessage a, byte cmd1) {
        synchronized (groupState) {
            GroupMessageStateMachine m = groupState.get(group);
            if (m == null) {
                m = new GroupMessageStateMachine();
                groupState.put(group, m);
                if (logger.isTraceEnabled()) {
                    logger.trace("{} created group {} state", address, group);
                }
            } else {
                if (lastMsgReceived <= m.getLastUpdated()) {
                    if (logger.isTraceEnabled()) {
                        logger.trace("{} using previous group {} state for {}", address, group, a);
                    }
                    return m.getPublish();
                }
            }

            if (logger.isTraceEnabled()) {
                logger.trace("{} updating group {} state to {}", address, group, a);
            }
            return (m.action(a, address, group, cmd1));
        }
    }

    /**
     * Initializes this device
     *
     * @param pollInterval the device poll interval to use
     */
    public void initialize(long pollInterval) {
        ModemDBEntry dbe = driver.getModemDB().getEntry(address);
        if (dbe != null) {
            ProductData productData = dbe.getProductData();
            if (productData != null) {
                updateProductData(productData);
            }
            if (!hasModemDBEntry()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("device {} found in the modem database.", address);
                }
                setHasModemDBEntry(true);
            }
            if (linkDB.isComplete()) {
                linkDB.clearRecords();
            }
            if (!hasValidPollingInterval()) {
                setPollInterval(pollInterval);
            }
            if (isPollable()) {
                startPolling();
            }
        } else {
            if (!address.isX10()) {
                logger.warn("device {} not found in the modem database. Did you forget to link?", address);
                setHasModemDBEntry(false);
                stopPolling();
            }
        }

        if (handler != null) {
            handler.updateThingStatus();
        }
    }

    @Override
    public String toString() {
        String s = address.toString();
        if (productData != null) {
            s += "|" + productData;
        } else {
            s += "|unknown device";
        }
        for (DeviceFeature f : features.values()) {
            s += "|" + f;
        }
        return s;
    }

    /**
     * Factory method for creating a InsteonDevice from a device address, driver and product data
     *
     * @param driver      the device driver
     * @param addr        the device address
     * @param productData the device product data
     * @return            the newly created InsteonDevice
     */
    public static @Nullable InsteonDevice makeDevice(Driver driver, InsteonAddress addr,
            @Nullable ProductData productData) {
        InsteonDevice dev = new InsteonDevice();
        dev.setAddress(addr);
        dev.setDriver(driver);

        if (productData != null) {
            DeviceType dt = productData.getDeviceType();
            if (dt == null) {
                logger.warn("unsupported product {} for {}", productData, addr);
                return null;
            }

            dev.instantiateFeatures(dt);
            dev.setFlags(dt.getFlags());
            dev.setProductData(productData);
        }
        return dev;
    }

    /**
     * Request queue entry helper class
     */
    @NonNullByDefault
    private class RQEntry implements Comparable<RQEntry> {
        private String name;
        private @Nullable DeviceFeature feature;
        private Msg msg;
        private long expirationTime;
        private boolean blocking;

        public RQEntry(DeviceFeature feature, Msg msg, long delay, boolean blocking) {
            this.name = feature.getName();
            this.feature = feature;
            this.msg = msg;
            this.blocking = blocking;
            setExpirationTime(delay);
        }

        public RQEntry(String name, Msg msg, long delay, boolean blocking) {
            this.name = name;
            this.feature = null;
            this.msg = msg;
            this.blocking = blocking;
            setExpirationTime(delay);
        }

        public String getName() {
            return name;
        }

        public @Nullable DeviceFeature getFeature() {
            return feature;
        }

        public Msg getMsg() {
            return msg;
        }

        public long getExpirationTime() {
            return expirationTime;
        }

        public boolean isBlocking() {
            return blocking;
        }

        public void setExpirationTime(long delay) {
            this.expirationTime = System.currentTimeMillis() + delay;
        }

        @Override
        public int compareTo(RQEntry a) {
            return (int) (expirationTime - a.expirationTime);
        }
    }
}
