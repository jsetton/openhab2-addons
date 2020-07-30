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
import org.openhab.binding.insteon.internal.message.InvalidMessageTypeException;
import org.openhab.binding.insteon.internal.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A PollHandler creates an Insteon message to query a particular
 * DeviceFeature of an Insteon device.
 *
 * @author Bernd Pfrommer - Initial contribution
 * @author Rob Nielsen - Port to openHAB 2 insteon binding
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
@SuppressWarnings("null")
public abstract class PollHandler extends FeatureBaseHandler {
    private static final Logger logger = LoggerFactory.getLogger(PollHandler.class);

    /**
     * Constructor
     *
     * @param f The device feature being polled
     */
    PollHandler(DeviceFeature f) {
        super(f);
    }

    /**
     * Creates Insteon message that can be used to poll a feature
     * via the Insteon network.
     *
     * @param device reference to the insteon device to be polled
     * @return Insteon query message or null if creation failed
     */
    public abstract @Nullable Msg makeMsg(InsteonDevice device);

    /**
     * A flexible, parameterized poll handler that can generate
     * most query messages. Provide the suitable parameters in
     * the device features file.
     */
    @NonNullByDefault
    public static class FlexPollHandler extends PollHandler {
        FlexPollHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable Msg makeMsg(InsteonDevice device) {
            Msg m = null;
            int cmd1 = getIntParameter("cmd1", 0);
            int cmd2 = getIntParameter("cmd2", 0);
            int ext = getIntParameter("ext", -1);
            long quietTime = getLongParameter("quiet", -1);
            try {
                // make message based on feature parameters
                if (ext == 0) {
                    m = device.makeStandardMessage((byte) 0x0F, (byte) cmd1, (byte) cmd2);
                } else if (ext == 1 || ext == 2) {
                    // set userData1 to d1 parameter if defined, fallback to group parameter
                    byte[] data = { (byte) getIntParameter("d1", getIntParameter("group", 0)),
                            (byte) getIntParameter("d2", 0), (byte) getIntParameter("d3", 0) };
                    if (ext == 1) {
                        m = device.makeExtendedMessage((byte) 0x1F, (byte) cmd1, (byte) cmd2, data);
                    } else {
                        m = device.makeExtendedMessageCRC2((byte) 0x1F, (byte) cmd1, (byte) cmd2, data);
                    }
                } else {
                    logger.warn("{}: handler misconfigured, no valid ext field specified", nm());
                }
                // override default message quiet time if parameter specified
                if (quietTime >= 0) {
                    m.setQuietTime(quietTime);
                }
            } catch (FieldException e) {
                logger.warn("error setting field in msg: ", e);
            } catch (InvalidMessageTypeException e) {
                logger.warn("invalid message ", e);
            }
            return m;
        }
    }

    @NonNullByDefault
    public static class NoPollHandler extends PollHandler {
        NoPollHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable Msg makeMsg(InsteonDevice device) {
            return null;
        }
    }

    /**
     * Factory method for creating handlers of a given name using java reflection
     *
     * @param name the name of the handler to create
     * @param params
     * @param f the feature for which to create the handler
     * @return the handler which was created
     */
    public static @Nullable <T extends PollHandler> T makeHandler(String name,
            Map<String, @Nullable String> params, DeviceFeature f) {
        String cname = PollHandler.class.getName() + "$" + name;
        try {
            Class<?> c = Class.forName(cname);
            @SuppressWarnings("unchecked")
            Class<? extends T> dc = (Class<? extends T>) c;
            T ph = dc.getDeclaredConstructor(DeviceFeature.class).newInstance(f);
            ph.addParameters(params);
            return ph;
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | IllegalArgumentException
                | InvocationTargetException | NoSuchMethodException | SecurityException e) {
            logger.warn("error trying to create message handler: {}", name, e);
        }
        return null;
    }
}
