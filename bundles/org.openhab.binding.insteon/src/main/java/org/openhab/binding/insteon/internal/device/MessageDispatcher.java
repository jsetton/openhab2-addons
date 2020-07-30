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

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.insteon.internal.message.FieldException;
import org.openhab.binding.insteon.internal.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Does preprocessing of messages to decide which handler should be called.
 *
 * @author Bernd Pfrommer - Initial contribution
 * @author Rob Nielsen - Port to openHAB 2 insteon binding
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
@SuppressWarnings("null")
public abstract class MessageDispatcher extends FeatureBaseHandler {
    private static final Logger logger = LoggerFactory.getLogger(MessageDispatcher.class);

    /**
     * Constructor
     *
     * @param f DeviceFeature to which this MessageDispatcher belongs
     */
    MessageDispatcher(DeviceFeature f) {
        super(f);
    }

    /**
     * Generic handling of incoming broadcast or cleanup messages
     *
     * @param msg  the message received
     * @param f    the device feature
     */
    protected void handleBroadcastOrCleanupMessage(Msg msg, DeviceFeature f) throws FieldException {
        // ALL_LINK_BROADCAST and ALL_LINK_CLEANUP have a valid Command1 field
        // but the CLEANUP_SUCCESS (of type ALL_LINK_BROADCAST!) message has cmd1 = 0x06 and
        // the cmd as the high byte of the toAddress.
        byte cmd1 = msg.getByte("command1");
        if (!msg.isCleanup() && cmd1 == 0x06) {
            cmd1 = msg.getAddress("toAddress").getHighByte();
        }
        int group = msg.getGroup();
        MessageHandler h = f.getMsgHandler(cmd1);
        if (h == null) {
            if (logger.isTraceEnabled()) {
                logger.trace("{}:{} ignoring msg as not for this feature", f.getDevice().getAddress(), f.getName());
            }
        } else if (h.isDuplicate(msg)) {
            if (logger.isTraceEnabled()) {
                logger.trace("{}:{} ignoring msg as duplicate", f.getDevice().getAddress(), f.getName());
            }
        } else if (!h.matchesGroup(group) || !h.matches(msg)) {
            if (logger.isTraceEnabled()) {
                logger.trace("{}:{} ignoring msg as matches group:{} filter:{}", f.getDevice().getAddress(),
                        f.getName(), h.matchesGroup(group), h.matches(msg));
            }
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("{}:{}->{} {} group:{}", f.getDevice().getAddress(), f.getName(),
                        h.getClass().getSimpleName(), msg.getType(), group != -1 ? group : "N/A");
            }
            h.handleMessage(group, cmd1, msg);
        }
    }

    /**
     * Generic handling of the incoming direct messages
     *
     * @param msg  the message received
     * @param f    the device feature
     */
    protected void handleDirectMessage(Msg msg, DeviceFeature f) throws FieldException {
        byte cmd1 = msg.getByte("command1");
        int key = msg.isAckOrNackOfDirect() ? 0x19 : cmd1; // use cmd 0x19 on DIRECT ACK/NACK reply messages
        int group = msg.getGroup();
        MessageHandler h = f.getOrDefaultMsgHandler(key);
        if (!h.isValid(msg)) {
            if (logger.isTraceEnabled()) {
                logger.trace("{}:{} ignoring msg as not valid", f.getDevice().getAddress(), f.getName());
            }
        } else if (!h.matchesGroup(group) || !h.matches(msg)) {
            if (logger.isTraceEnabled()) {
                logger.trace("{}:{} ignoring msg as matches group:{} filter:{}", f.getDevice().getAddress(),
                        f.getName(), h.matchesGroup(group), h.matches(msg));
            }
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("{}:{}->{} {} group:{}", f.getDevice().getAddress(), f.getName(),
                        h.getClass().getSimpleName(), msg.getType(), group != -1 ? group : "N/A");
            }
            h.handleMessage(group, cmd1, msg);
        }
    }

    /**
     * Dispatches message
     *
     * @param msg Message to dispatch
     * @return true if this message was found to be a reply to a direct message,
     *         and was claimed by one of the handlers
     */
    public abstract boolean dispatch(Msg msg);

    //
    //
    // ------------ implementations of MessageDispatchers start here ------------------
    //
    //

    @NonNullByDefault
    public static class DefaultDispatcher extends MessageDispatcher {
        DefaultDispatcher(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean dispatch(Msg msg) {
            try {
                if (msg.isAllLinkCleanupAckOrNack()) {
                    // Had cases when a KeypadLinc would send an ALL_LINK_CLEANUP_ACK
                    // in response to a direct status query message
                    return false;
                }
                if (msg.isBroadcast() || msg.isCleanup()) {
                    handleBroadcastOrCleanupMessage(msg, feature);
                    return false;
                }
                if (msg.isDirect() || feature.isMyDirectAck(msg)) {
                    // handle DIRECT and my ACK messages queried by this feature
                    handleDirectMessage(msg, feature);
                }
                return feature.isMyDirectAckOrNack(msg);
            } catch (FieldException e) {
                logger.warn("error parsing, dropping msg {}", msg);
            }
            return false;
        }
    }

    @NonNullByDefault
    public static class DefaultGroupDispatcher extends MessageDispatcher {
        DefaultGroupDispatcher(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean dispatch(Msg msg) {
            try {
                if (feature.isMyDirectAck(msg)) {
                    // get connected features to handle my DIRECT ACK messages
                    for (DeviceFeature f : feature.getConnectedFeatures()) {
                        handleDirectMessage(msg, f);
                    }
                }
                return feature.isMyDirectAckOrNack(msg);
            } catch (FieldException e) {
                logger.warn("error parsing, dropping msg {}", msg);
            }
            return false;
        }
    }

    @NonNullByDefault
    public static class PollGroupDispatcher extends MessageDispatcher {
        PollGroupDispatcher(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean dispatch(Msg msg) {
            if (feature.isMyDirectAckOrNack(msg)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("{}:{} got poll {}", feature.getDevice().getAddress(), feature.getName(),
                            msg.getType());
                }
                return true;
            }
            return false;
        }
    }

    @NonNullByDefault
    public static class SimpleDispatcher extends MessageDispatcher {
        SimpleDispatcher(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean dispatch(Msg msg) {
            try {
                if (msg.isAllLinkCleanupAckOrNack()) {
                    // Had cases when a KeypadLinc would send an ALL_LINK_CLEANUP_ACK
                    // in response to a direct status query message
                    return false;
                }
                if (msg.isBroadcast() || msg.isCleanup()) {
                    handleBroadcastOrCleanupMessage(msg, feature);
                    return false;
                }
                if (msg.isDirect() || msg.isAckOrNackOfDirect()) {
                    // handle DIRECT and any ACK/NACK messages
                    handleDirectMessage(msg, feature);
                }
                return feature.isMyDirectAckOrNack(msg);
            } catch (FieldException e) {
                logger.warn("error parsing, dropping msg {}", msg);
            }
            return false;
        }
    }

    @NonNullByDefault
    public static class X10Dispatcher extends MessageDispatcher {
        X10Dispatcher(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean dispatch(Msg msg) {
            try {
                byte rawX10 = msg.getByte("rawX10");
                int cmd = (rawX10 & 0x0f);
                MessageHandler h = feature.getOrDefaultMsgHandler(cmd);
                if (logger.isDebugEnabled()) {
                    logger.debug("{}:{}->{} {}", feature.getDevice().getAddress(), feature.getName(),
                            h.getClass().getSimpleName(), msg.getType());
                }
                if (h.matches(msg)) {
                    h.handleMessage(-1, (byte) cmd, msg);
                }
            } catch (FieldException e) {
                logger.warn("error parsing, dropping msg {}", msg);
            }
            return false;
        }
    }

    @NonNullByDefault
    public static class PassThroughDispatcher extends MessageDispatcher {
        PassThroughDispatcher(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean dispatch(Msg msg) {
            MessageHandler h = feature.getDefaultMsgHandler();
            if (h.matches(msg)) {
                if (logger.isTraceEnabled()) {
                    logger.trace("{}:{}->{} {}", feature.getDevice().getAddress(), feature.getName(),
                            h.getClass().getSimpleName(), msg.getType());
                }
                h.handleMessage(-1, (byte) 0x01, msg);
            }
            return false;
        }
    }

    /**
     * Drop all incoming messages silently
     */
    @NonNullByDefault
    public static class NoOpDispatcher extends MessageDispatcher {
        NoOpDispatcher(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean dispatch(Msg msg) {
            return false;
        }
    }

    /**
     * Factory method for creating a dispatcher of a given name using java reflection
     *
     * @param name the name of the dispatcher to create
     * @param params
     * @param f the feature for which to create the dispatcher
     * @return the handler which was created
     */
    public static @Nullable <T extends MessageDispatcher> T makeHandler(String name,
            Map<String, @Nullable String> params, DeviceFeature f) {
        String cname = MessageDispatcher.class.getName() + "$" + name;
        try {
            Class<?> c = Class.forName(cname);
            @SuppressWarnings("unchecked")
            Class<? extends T> dc = (Class<? extends T>) c;
            T md = dc.getDeclaredConstructor(DeviceFeature.class).newInstance(f);
            md.addParameters(params);
            return md;
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | IllegalArgumentException
                | InvocationTargetException | NoSuchMethodException | SecurityException e) {
            logger.warn("error trying to create dispatcher: {}", name, e);
        }
        return null;
    }
}
