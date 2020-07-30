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

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.State;
import org.openhab.binding.insteon.internal.config.InsteonChannelConfiguration;
import org.openhab.binding.insteon.internal.device.DeviceFeatureListener.StateChangeType;
import org.openhab.binding.insteon.internal.message.Msg;
import org.openhab.binding.insteon.internal.utils.ParsingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A DeviceFeature represents a certain feature (trait) of a given Insteon device, e.g. something
 * operating under a given InsteonAddress that can be manipulated (relay) or read (sensor).
 *
 * The DeviceFeature does the processing of incoming messages, and handles commands for the
 * particular feature it represents.
 *
 * It uses four mechanisms for that:
 *
 * 1) MessageDispatcher: makes high level decisions about an incoming message and then runs the
 * 2) MessageHandler: further processes the message, updates state etc
 * 3) CommandHandler: translates commands from the openhab bus into an Insteon message.
 * 4) PollHandler: creates an Insteon message to query the DeviceFeature
 *
 * Lastly, DeviceFeatureListeners can register with the DeviceFeature to get notifications when
 * the state of a feature has changed. In practice, a DeviceFeatureListener corresponds to an
 * openHAB item.
 *
 * The character of a DeviceFeature is thus given by a set of message and command handlers.
 * A FeatureTemplate captures exactly that: it says what set of handlers make up a DeviceFeature.
 *
 * DeviceFeatures are added to a new device by referencing a FeatureTemplate (defined in device_features.xml)
 * from the Device definition file (device_types.xml).
 *
 * @author Daniel Pfrommer - Initial contribution
 * @author Bernd Pfrommer - openHAB 1 insteonplm binding
 * @author Rob Nielsen - Port to openHAB 2 insteon binding
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
@SuppressWarnings("null")
public class DeviceFeature {
    public static enum QueryStatus {
        NEVER_QUERIED,
        QUERY_CREATED,
        QUERY_QUEUED,
        QUERY_PENDING,
        QUERY_ANSWERED,
        QUERY_EXPIRED,
        NOT_POLLABLE
    }

    private static final Logger logger = LoggerFactory.getLogger(DeviceFeature.class);

    private static Map<String, FeatureTemplate> features = new HashMap<>();

    private InsteonDevice device = new InsteonDevice();
    private String name = "INVALID_FEATURE";
    private String type = "INVALID_FEATURE_TYPE";
    private int directAckTimeout = 6000;
    private QueryStatus queryStatus = QueryStatus.NOT_POLLABLE;

    private @Nullable MessageHandler defaultMsgHandler = new MessageHandler.DefaultMsgHandler(this);
    private @Nullable CommandHandler defaultCommandHandler = new CommandHandler.WarnCommandHandler(this);
    private @Nullable PollHandler pollHandler = null;
    private @Nullable MessageDispatcher dispatcher = null;
    private @Nullable DeviceFeature groupFeature = null;
    private @Nullable Double lastMsgValue = null;
    private @Nullable State lastState = null;

    private Map<String, @Nullable String> parameters = new HashMap<>();
    private Map<Integer, @Nullable MessageHandler> msgHandlers = new HashMap<>();
    private Map<Class<? extends Command>, @Nullable CommandHandler> commandHandlers = new HashMap<>();
    private List<DeviceFeatureListener> listeners = new ArrayList<>();
    private List<DeviceFeature> connectedFeatures = new ArrayList<>();

    /**
     * Constructor
     *
     * @param type feature type name
     */
    public DeviceFeature(String type) {
        this.type = type;
    }

    // various simple getters
    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public Map<String, @Nullable String> getParameters() {
        return parameters;
    }

    public @Nullable String getParameter(String key, @Nullable String def) {
        return parameters.getOrDefault(key, def);
    }

    public boolean getBooleanParameter(String key, boolean def) {
        String val = parameters.get(key);
        return val == null ? def : val.equals("true");
    }

    public int getIntParameter(String key, int def) {
        String val = parameters.get(key);
        try {
            if (val != null) {
                return Integer.parseInt(val);
            }
        } catch (NumberFormatException e) {
            logger.warn("{}: malformed int parameter in device feature: {}", name, key);
        }
        return def;
    }

    public @Nullable Double getLastMsgValue() {
        return lastMsgValue;
    }

    public double getDoubleLastMsgValue(double def) {
        return lastMsgValue == null ? def : lastMsgValue.doubleValue();
    }

    public int getIntLastMsgValue(int def) {
        return lastMsgValue == null ? def : lastMsgValue.intValue();
    }

    public @Nullable State getLastState() {
        return lastState;
    }

    public synchronized QueryStatus getQueryStatus() {
        return queryStatus;
    }

    public InsteonDevice getDevice() {
        return device;
    }

    public boolean isFeatureGroup() {
        return !connectedFeatures.isEmpty();
    }

    public boolean isPartOfFeatureGroup() {
        return groupFeature != null;
    }

    public boolean isControllerFeature() {
        String linkSupport = getParameter("link", "");
        return linkSupport.equals("both") || linkSupport.equals("controller");
    }

    public boolean isResponderFeature() {
        String linkSupport = getParameter("link", "");
        return linkSupport.equals("both") || linkSupport.equals("responder");
    }

    public boolean isEventFeature() {
        return getBooleanParameter("event", false);
    }

    public boolean isHiddenFeature() {
        return getBooleanParameter("hidden", false);
    }

    public boolean isStatusFeature() {
        return getBooleanParameter("status", false);
    }

    public int getDirectAckTimeout() {
        return directAckTimeout;
    }

    public @Nullable MessageHandler getDefaultMsgHandler() {
        return defaultMsgHandler;
    }

    public @Nullable MessageHandler getMsgHandler(int key) {
        return msgHandlers.get(key);
    }

    public @Nullable MessageHandler getOrDefaultMsgHandler(int key) {
        return msgHandlers.getOrDefault(key, defaultMsgHandler);
    }

    public @Nullable PollHandler getPollHandler() {
        return pollHandler;
    }

    public List<DeviceFeature> getConnectedFeatures() {
        return (connectedFeatures);
    }

    public @Nullable DeviceFeature getGroupFeature() {
        return groupFeature;
    }

    // various simple setters
    public void setPollHandler(@Nullable PollHandler h) {
        pollHandler = h;
    }

    public void setDevice(InsteonDevice d) {
        device = d;
    }

    public void setName(String s) {
        name = s;
    }

    public void setMessageDispatcher(@Nullable MessageDispatcher md) {
        dispatcher = md;
    }

    public void setDefaultCommandHandler(@Nullable CommandHandler ch) {
        defaultCommandHandler = ch;
    }

    public void setDefaultMsgHandler(@Nullable MessageHandler mh) {
        defaultMsgHandler = mh;
    }

    public void setGroupFeature(@Nullable DeviceFeature f) {
        groupFeature = f;
    }

    public synchronized void setLastMsgValue(double value) {
        if (logger.isTraceEnabled()) {
            logger.trace("{} set last message value to: {}", name, value);
        }
        lastMsgValue = value;
    }

    public synchronized void setLastState(State state) {
        if (logger.isTraceEnabled()) {
            logger.trace("{} set last state to: {}", name, state);
        }
        lastState = state;
    }

    public synchronized void setQueryStatus(QueryStatus status) {
        if (logger.isTraceEnabled()) {
            logger.trace("{} set query status to: {}", name, status);
        }
        queryStatus = status;
    }

    public void initializeQueryStatus() {
        // set query status to never queried if feature pollable,
        // otherwise to not pollable if not already configured
        if (pollHandler != null && pollHandler.makeMsg(device) != null) {
            setQueryStatus(QueryStatus.NEVER_QUERIED);
        } else if (queryStatus != QueryStatus.NOT_POLLABLE) {
            setQueryStatus(QueryStatus.NOT_POLLABLE);
        }
    }

    public void setTimeout(@Nullable String s) {
        if (s != null && !s.isEmpty()) {
            try {
                directAckTimeout = Integer.parseInt(s);
                if (logger.isTraceEnabled()) {
                    logger.trace("ack timeout set to {}", directAckTimeout);
                }
            } catch (NumberFormatException e) {
                logger.warn("invalid number for timeout: {}", s);
            }
        }
    }

    public void setParameter(String key, @Nullable String val) {
        synchronized (parameters) {
            parameters.put(key, val);
        }
    }

    public void addParameters(Map<String, @Nullable String> map) {
        synchronized (parameters) {
            parameters.putAll(map);
        }
    }

    /**
     * Add a listener (item) to a device feature
     *
     * @param l the listener
     */
    public void addListener(DeviceFeatureListener l) {
        synchronized (listeners) {
            for (DeviceFeatureListener m : listeners) {
                if (m.getChannelName().equals(l.getChannelName())) {
                    return;
                }
            }
            listeners.add(l);
        }
    }

    /**
     * Adds a connected feature such that this DeviceFeature can
     * act as a feature group
     *
     * @param f the device feature connected to this feature
     */
    public void addConnectedFeature(DeviceFeature f) {
        connectedFeatures.add(f);
    }

    /**
     * Checks if a DeviceFeatureListener is defined for this feature
     *
     * @return true if at least one listener is defined
     */
    public boolean hasListeners() {
        if (!listeners.isEmpty()) {
            return true;
        }
        for (DeviceFeature f : connectedFeatures) {
            if (f.hasListeners()) {
                return true;
            }
        }
        return false;
    }

    /**
     * removes a DeviceFeatureListener from this feature
     *
     * @param channelName listener channel name to remove
     * @return true if a listener was removed
     */
    public boolean removeListener(String channelName) {
        synchronized (listeners) {
            for (Iterator<DeviceFeatureListener> it = listeners.iterator(); it.hasNext();) {
                DeviceFeatureListener fl = it.next();
                if (fl.getChannelName().equals(channelName)) {
                    it.remove();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if a DeviceFeatureListener is defined for this feature
     * based on channel name
     *
     * @param  channelName listener channel name to check
     * @return true if listener is deined
     */
    public boolean hasFeatureListener(String channelName) {
        synchronized (listeners) {
            for (DeviceFeatureListener fl : listeners) {
                if (fl.getChannelName().equals(channelName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Gets a listener based on channel name
     *
     * @param  channelName listener channel name to check
     * @return listener if deined, otherwise null
     */
    public @Nullable DeviceFeatureListener getListener(String channelName) {
        synchronized (listeners) {
            for (DeviceFeatureListener fl : listeners) {
                if (fl.getChannelName().equals(channelName)) {
                    return fl;
                }
            }
        }
        return null;
    }

    /**
     * Checks if a message is a successful response queried by this feature
     *
     * @param msg
     * @return true if my direct ack
     */
    public boolean isMyDirectAck(Msg msg) {
        return msg.isAckOfDirect() && !msg.isReplayed() && getQueryStatus() == QueryStatus.QUERY_PENDING;
    }

    /**
     * Checks if a message is a response queried by this feature
     *
     * @param msg
     * @return true if my direct ack or nack
     */
    public boolean isMyDirectAckOrNack(Msg msg) {
        return msg.isAckOrNackOfDirect() && !msg.isReplayed() && getQueryStatus() == QueryStatus.QUERY_PENDING;
    }

    /**
     * Called when message is incoming. Dispatches message according to message dispatcher
     *
     * @param msg The message to dispatch
     * @return true if dispatch successful
     */
    public boolean handleMessage(Msg msg) {
        if (dispatcher == null) {
            logger.warn("{}:{} no dispatcher for msg {}", device.getAddress(), name, msg);
            return false;
        }
        if (logger.isTraceEnabled()) {
            logger.trace("{}:{} handling message using dispatcher {}", device.getAddress(), name,
                    dispatcher.getClass().getSimpleName());
        }
        return dispatcher.dispatch(msg);
    }

    /**
     * Called when an openhab command arrives for this device feature
     *
     * @param c the binding config of the item which sends the command
     * @param cmd the command to be exectued
     */
    public void handleCommand(InsteonChannelConfiguration c, Command cmd) {
        Class<? extends Command> key = cmd.getClass();
        CommandHandler h = commandHandlers.getOrDefault(key, defaultCommandHandler);
        if (h.canHandle(cmd)) {
            if (logger.isTraceEnabled()) {
                logger.trace("{}:{} handling command {} using handler {}", device.getAddress(), name,
                        key.getSimpleName(), h.getClass().getSimpleName());
            }
            h.handleCommand(c, cmd, device);
        } else {
            logger.warn("{}:{} command {} cannot be processed by handler {}", device.getAddress(), name,
                    key.getSimpleName(), h.getClass().getSimpleName());
        }
    }

    /**
     * Make a poll message using the configured poll message handler
     *
     * @return the poll message
     */
    public @Nullable Msg makePollMsg() {
        if (pollHandler == null) {
            return null;
        }
        if (logger.isTraceEnabled()) {
            logger.trace("{}:{} making poll msg using handler {}", device.getAddress(), name,
                    pollHandler.getClass().getSimpleName());
        }
        Msg m = pollHandler.makeMsg(device);
        return m;
    }

    /**
     * Publish new state to all device feature listeners
     *
     * @param newState state to be published
     * @param changeType what kind of changes to publish
     */
    public void publish(State newState, StateChangeType changeType) {
        setLastState(newState);
        if (logger.isDebugEnabled()) {
            logger.debug("{}:{} publishing: {}", device.getAddress(), name, newState);
        }
        synchronized (listeners) {
            for (DeviceFeatureListener listener : listeners) {
                listener.stateChanged(newState, changeType);
            }
        }
    }

    /**
     * Trigger channel event to all device feature listeners
     *
     * @param event name of the event to be triggered
     */
    public void triggerEvent(String event) {
        if (!isEventFeature()) {
            logger.warn("{}:{} not configured to handle triggered event", device.getAddress(), name);
            return;
        }
        if (logger.isDebugEnabled()) {
            logger.debug("{}:{} triggering event: {}", device.getAddress(), name, event);
        }
        synchronized (listeners) {
            for (DeviceFeatureListener listener : listeners) {
                listener.triggerEvent(event);
            }
        }
    }

    /**
     * Trigger a poll at this feature, group feature or device level,
     *  in order of precedence depending on pollability
     *
     * @param delay scheduling delay (in milliseconds)
     */
    public void triggerPoll(long delay) {
        // trigger feature poll if pollable
        Msg m = makePollMsg();
        if (m != null) {
            if (logger.isTraceEnabled()) {
                logger.trace("{}:{} triggering poll on this feature", device.getAddress(), name);
            }
            device.enqueueDelayedRequest(m, this, delay);
            return;
        }
        // trigger group feature poll if defined and pollable, as fallback
        DeviceFeature gf = getGroupFeature();
        Msg gm = gf == null ? null : gf.makePollMsg();
        if (gm != null) {
            if (logger.isTraceEnabled()) {
                logger.trace("{}:{} triggering poll on group feature {}", device.getAddress(), name, gf.getName());
            }
            device.enqueueDelayedRequest(gm, gf, delay);
            return;
        }
        // trigger device poll limiting to responder features, as last option
        device.doPollResponders(delay);
    }

    /**
     * Checks if this feature or any its connected features has a responder feature
     *
     * @return true if at least one feature is a responder
     */
    public boolean hasResponderFeatures() {
        if (isResponderFeature()) {
            return true;
        }
        for (DeviceFeature f : connectedFeatures) {
            if (f.hasResponderFeatures()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adjusts related devices from a specific device feature listener
     * based on a channel name for a given command
     *
     * @param  channelName listener channel name to adjust
     * @param  cmd         command to adjust to
     */
    public void adjustRelatedDevices(String channelName, Command cmd) {
        synchronized (listeners) {
            DeviceFeatureListener listener = getListener(channelName);
            if (listener != null) {
                listener.adjustRelatedDevices(cmd);
            }
        }
    }

    /**
     * Polls related devices from all device feature listeners
     */
    public void pollRelatedDevices() {
        synchronized (listeners) {
            for (DeviceFeatureListener listener : listeners) {
                listener.pollRelatedDevices();
            }
        }
    }

    /**
     * Polls related devices from a specific device feature listener
     * based on channel name
     *
     * @param  channelName listener channel name to poll
     */
    public void pollRelatedDevices(String channelName) {
        synchronized (listeners) {
            DeviceFeatureListener listener = getListener(channelName);
            if (listener != null) {
                listener.pollRelatedDevices();
            }
        }
    }

    /**
     * Updates channel configs from all device feature listeners
     */
    public void updateChannelConfigs() {
        synchronized (listeners) {
            for (DeviceFeatureListener listener : listeners) {
                listener.updateChannelConfig();
            }
        }
    }

    /**
     * Adds a message handler to this device feature.
     *
     * @param cm1 The insteon cmd1 of the incoming message for which the handler should be used
     * @param handler the handler to invoke
     */
    public void addMessageHandler(int cm1, @Nullable MessageHandler handler) {
        synchronized (msgHandlers) {
            msgHandlers.put(cm1, handler);
        }
    }

    /**
     * Adds a command handler to this device feature
     *
     * @param c the command for which this handler is invoked
     * @param handler the handler to call
     */
    public void addCommandHandler(Class<? extends Command> c, @Nullable CommandHandler handler) {
        synchronized (commandHandlers) {
            commandHandlers.put(c, handler);
        }
    }

    /**
     * Returns this device feature information as a string
     */
    @Override
    public String toString() {
        String s = name + "->" + type;
        if (!parameters.isEmpty()) {
            s += parameters;
        }
        s += "(" + listeners.size() + ":" + commandHandlers.size() + ":" + msgHandlers.size() + ")";
        return s;
    }

    /**
     * Factory method for creating DeviceFeature
     *
     * @param type The device feature type to create
     * @return The newly created DeviceFeature, or null if requested DeviceFeature does not exist.
     */
    @Nullable
    public static DeviceFeature makeDeviceFeature(String type) {
        DeviceFeature f = null;
        synchronized (features) {
            if (features.containsKey(type)) {
                f = features.get(type).build();
            } else {
                logger.warn("unimplemented feature type requested: {}", type);
            }
        }
        return f;
    }

    /**
     * Reads the features templates from an input stream and puts them in global map
     *
     * @param input the input stream from which to read the feature templates
     */
    public static void readFeatureTemplates(InputStream input) {
        try {
            List<FeatureTemplate> featureTemplates = FeatureTemplateLoader.readTemplates(input);
            synchronized (features) {
                for (FeatureTemplate f : featureTemplates) {
                    features.put(f.getName(), f);
                }
            }
        } catch (IOException e) {
            logger.warn("IOException while reading device features", e);
        } catch (ParsingException e) {
            logger.warn("Parsing exception while reading device features", e);
        }
    }

    /**
     * Reads the feature templates from a file and adds them to a global map
     *
     * @param file name of the file to read from
     */
    public static void readFeatureTemplates(String file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            readFeatureTemplates(fis);
        } catch (FileNotFoundException e) {
            logger.warn("cannot read feature templates from file {} ", file, e);
        }
    }

    /**
     * static initializer
     */
    static {
        // read features from xml file and store them in a map
        InputStream input = DeviceFeature.class.getResourceAsStream("/device_features.xml");
        readFeatureTemplates(input);
    }
}
