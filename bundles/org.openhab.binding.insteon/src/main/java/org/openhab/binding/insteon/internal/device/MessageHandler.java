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

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.Map;

import javax.measure.Unit;
import javax.measure.quantity.Dimensionless;
import javax.measure.quantity.Energy;
import javax.measure.quantity.Power;
import javax.measure.quantity.Temperature;
import javax.measure.quantity.Time;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.DateTimeType;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.OpenClosedType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.library.unit.ImperialUnits;
import org.eclipse.smarthome.core.library.unit.SmartHomeUnits;
import org.eclipse.smarthome.core.library.unit.SIUnits;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.insteon.internal.device.DeviceFeatureListener.StateChangeType;
import org.openhab.binding.insteon.internal.device.GroupMessageStateMachine.GroupMessage;
import org.openhab.binding.insteon.internal.message.FieldException;
import org.openhab.binding.insteon.internal.message.Msg;
import org.openhab.binding.insteon.internal.utils.BitwiseUtils;
import org.openhab.binding.insteon.internal.utils.ByteUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A message handler processes incoming Insteon messages and reacts by publishing
 * corresponding messages on the openhab bus, updating device state etc.
 *
 * @author Daniel Pfrommer - Initial contribution
 * @author Bernd Pfrommer - openHAB 1 insteonplm binding
 * @author Rob Nielsen - Port to openHAB 2 insteon binding
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
@SuppressWarnings("null")
public abstract class MessageHandler extends FeatureBaseHandler {
    private static final Logger logger = LoggerFactory.getLogger(MessageHandler.class);

    /**
     * Constructor
     *
     * @param f The device feature for the incoming message
     */
    MessageHandler(DeviceFeature f) {
        super(f);
    }

    /**
     * Method that processes incoming message. The cmd1 parameter
     * has been extracted earlier already (to make a decision which message handler to call),
     * and is passed in as an argument so cmd1 does not have to be extracted from the message again.
     *
     * @param group all-link group or -1 if not specified
     * @param cmd1 the insteon cmd1 field
     * @param msg the received insteon message
     */
    public abstract void handleMessage(int group, byte cmd1, Msg msg);

    /**
     * Check if group matches
     *
     * @param group group to test for
     * @return true if group matches or no group is specified/provided
     */
    public boolean matchesGroup(int group) {
        int g = getGroup();
        return (g == -1 || group == -1 || g == group);
    }

    /**
     * Retrieve group parameter or -1 if no group is specified
     *
     * @return group parameter
     */
    public int getGroup() {
        return (getIntParameter("group", -1));
    }

    /**
     * Test if parameter matches value
     *
     * @param msg message to search
     * @param field field name to match
     * @param param name of parameter to match
     * @return true if parameter matches
     * @throws FieldException if field not there
     */
    private boolean testMatch(Msg msg, String field, String param) throws FieldException {
        int mp = getIntParameter(param, -1);
        // parameter not filtered for, declare this a match!
        if (mp == -1) {
            return (true);
        }
        byte value = msg.getByte(field);
        return (value == mp);
    }

    /**
     * Test if message matches the filter parameters
     *
     * @param msg message to be tested against
     * @return true if message matches
     */
    public boolean matches(Msg msg) {
        try {
            int ext = getIntParameter("ext", -1);
            if (ext != -1) {
                if ((!msg.isExtended() && ext != 0) || (msg.isExtended() && ext != 1 && ext != 2)) {
                    return (false);
                }
                if (!testMatch(msg, "command1", "cmd1")) {
                    return (false);
                }
            }
            if (!testMatch(msg, "command2", "cmd2")) {
                return (false);
            }
            if (!testMatch(msg, "userData1", "d1")) {
                return (false);
            }
            if (!testMatch(msg, "userData2", "d2")) {
                return (false);
            }
            if (!testMatch(msg, "userData3", "d3")) {
                return (false);
            }
        } catch (FieldException e) {
            logger.warn("error matching message: {}", msg, e);
            return (false);
        }
        return (true);
    }

    /**
     * Determines if an incoming ALL LINK message is a duplicate
     *
     * @param msg the received ALL LINK message
     * @return true if this message is a duplicate
     */
    protected boolean isDuplicate(Msg msg) {
        boolean isDuplicate = false;
        try {
            if (msg.isAllLinkBroadcast()) {
                int group = msg.getGroup();
                byte cmd1 = msg.getByte("command1");
                // if the command is 0x06, then it's success message
                // from the original broadcaster, with which the device
                // confirms that it got all cleanup replies successfully.
                GroupMessage gm = (cmd1 == 0x06) ? GroupMessage.SUCCESS : GroupMessage.BCAST;
                isDuplicate = !feature.getDevice().getGroupState(group, gm, cmd1);
            } else if (msg.isCleanup()) {
                int group = msg.getGroup();
                isDuplicate = !feature.getDevice().getGroupState(group, GroupMessage.CLEAN, (byte) 0x00);
            } else if (msg.isBroadcast()) {
                byte cmd1 = msg.getByte("command1");
                isDuplicate = !feature.getDevice().getBroadcastState(cmd1);
            }
        } catch (IllegalArgumentException e) {
            logger.warn("cannot parse msg: {}", msg, e);
        } catch (FieldException e) {
            logger.warn("cannot parse msg: {}", msg, e);
        }
        return (isDuplicate);
    }

    /**
     * Determines if an incoming DIRECT message is valid
     *
     * @param msg the received DIRECT message
     * @return true if this message is valid
     */
    protected boolean isValid(Msg msg) {
        int ext = getIntParameter("ext", -1);
        // extended message crc is only included in incoming message when using the newer 2-byte method
        if (ext == 2) {
            return msg.hasValidCRC2();
        }
        return (true);
    }

    //
    //
    // ---------------- the various message handlers start here -------------------
    //
    //

    /**
     * Default message handler
     */
    @NonNullByDefault
    public static class DefaultMsgHandler extends MessageHandler {
        DefaultMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            if (logger.isDebugEnabled()) {
                logger.debug("{}: ignoring unimpl message with cmd1 {}", nm(), ByteUtils.getHexString(cmd1));
            }
        }
    }

    /**
     * No-op message handler
     */
    @NonNullByDefault
    public static class NoOpMsgHandler extends MessageHandler {
        NoOpMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            if (logger.isTraceEnabled()) {
                logger.trace("{}: ignoring message with cmd1 {}", nm(), ByteUtils.getHexString(cmd1));
            }
        }
    }

    /**
     * Trigger poll message handler
     */
    @NonNullByDefault
    public static class TriggerPollMsgHandler extends MessageHandler {
        TriggerPollMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            // trigger poll with delay based on parameter, defaulting to 0 ms
            long delay = getLongParameter("delay", 0L);
            feature.triggerPoll(delay);
        }
    }

    /**
     * Custom state abstract message handler based of parameters
     */
    @NonNullByDefault
    public abstract static class CustomMsgHandler extends MessageHandler {
        private StateChangeType changeType = StateChangeType.CHANGED;

        CustomMsgHandler(DeviceFeature f) {
            super(f);
        }

        public void setStateChangeType(StateChangeType changeType) {
            this.changeType = changeType;
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            try {
                // extract raw value from message
                int raw = getRawValue(group, msg);
                // apply mask and right shift bit manipulation
                int cooked = (raw & getIntParameter("mask", 0xFF)) >> getIntParameter("rshift", 0);
                // multiply with factor and add offset
                double value = cooked * getDoubleParameter("factor", 1.0) + getDoubleParameter("offset", 0.0);
                // get state to publish
                State state = getState(group, value);
                // store extracted cooked message value
                feature.setLastMsgValue(value);
                // publish state if defined
                if (state != null) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("{}: device {} {} changed to {}", nm(), feature.getDevice().getAddress(),
                                feature.getName(), state);
                    }
                    feature.publish(state, changeType);
                }
            } catch (FieldException e) {
                logger.warn("{}: error parsing msg {}", nm(), msg, e);
            }
        }

        private int getRawValue(int group, Msg msg) throws FieldException {
            // determine data field name based on parameter, default to cmd2 if is standard message
            String field = getStringParameter("field", !msg.isExtended() ? "command2" : "");
            if (field.equals("")) {
                throw new FieldException("handler misconfigured, no field parameter specified!");
            }
            if (field.startsWith("address") && !msg.isBroadcast()) {
                throw new FieldException("not broadcast msg, cannot use address bytes!");
            }
            // return raw value based on field name
            switch (field) {
                case "group":
                    return group;
                case "addressHighByte":
                    // return broadcast address high byte value
                    return msg.getAddress("toAddress").getHighByte() & 0xFF;
                case "addressMiddleByte":
                    // return broadcast address middle byte value
                    return msg.getAddress("toAddress").getMiddleByte() & 0xFF;
                case "addressLowByte":
                    // return broadcast address low byte value
                    return msg.getAddress("toAddress").getLowByte() & 0xFF;
                default:
                    // return integer value starting from field name up to 4-bytes in size based on parameter
                    return msg.getBytesAsInt(field, getIntParameter("num_bytes", 1));
            }
        }

        public abstract @Nullable State getState(int group, double value);
    }

    /**
     * Custom bitmask mmessage handler based of parameters
     */
    @NonNullByDefault
    public static class CustomBitmaskMsgHandler extends CustomMsgHandler {
        CustomBitmaskMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            State state = null;
            // get bit number based on parameter
            int bit = getBitNumber();
            // get bit flag state from bitmask, if bit defined
            if (bit != -1) {
                boolean isSet = BitwiseUtils.isBitFlagSet((int) value, bit);
                state = getBitFlagState(isSet);
            } else {
                logger.debug("{}: no valid bit number defined for {}", nm(), feature.getName());
            }
            return state;
        }

        public int getBitNumber() {
            int bit = getIntParameter("bit", -1);
            // return bit if valid (0-7), otherwise -1
            return bit >= 0 && bit <= 7 ? bit : -1;
        }

        public State getBitFlagState(boolean isSet) {
            return isSet ^ getBooleanParameter("inverted", false) ? OnOffType.ON : OnOffType.OFF;
        }
    }

    /**
     * Custom cache mmessage handler based of parameters
     */
    @NonNullByDefault
    public static class CustomCacheMsgHandler extends CustomMsgHandler {
        CustomCacheMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            // only cache extracted message value
            // mostly used for hidden features which are used by others
            return null;
        }
    }

    /**
     * Custom decimal type message handler based of parameters
     */
    @NonNullByDefault
    public static class CustomDecimalMsgHandler extends CustomMsgHandler {
        CustomDecimalMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            return new DecimalType(value);
        }
    }

    /**
     * Custom on/off type message handler based of parameters
     */
    @NonNullByDefault
    public static class CustomOnOffMsgHandler extends CustomMsgHandler {
        CustomOnOffMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            int onLevel =  getIntParameter("on", 0xFF);
            int offLevel = getIntParameter("off", 0x00);
            return value == onLevel ? OnOffType.ON : value == offLevel ? OnOffType.OFF : null;
        }
    }

    /**
     * Custom percent type message handler based of parameters
     */
    @NonNullByDefault
    public static class CustomPercentMsgHandler extends CustomMsgHandler {
        CustomPercentMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            int minValue = getIntParameter("min", 0x00);
            int maxValue = getIntParameter("max", 0xFF);
            double clampValue = Math.max(minValue, Math.min(maxValue, value));
            int level = (int) Math.round((clampValue - minValue) / (maxValue - minValue) * 100);
            return new PercentType(level);
        }
    }

    /**
     * Custom dimensionless quantity type message handler based of parameters
     */
    @NonNullByDefault
    public static class CustomDimensionlessMsgHandler extends CustomMsgHandler {
        CustomDimensionlessMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            int minValue = getIntParameter("min", 0);
            int maxValue = getIntParameter("max", 100);
            double clampValue = Math.max(minValue, Math.min(maxValue, value));
            int level = (int) Math.round((clampValue - minValue) * 100 / (maxValue - minValue));
            return new QuantityType<Dimensionless>(level, SmartHomeUnits.PERCENT);
        }
    }

    /**
     * Custom temperature quantity type message handler based of parameters
     */
    @NonNullByDefault
    public static class CustomTemperatureMsgHandler extends CustomMsgHandler {
        CustomTemperatureMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            Unit<Temperature> unit = getTemperatureUnit();
            return new QuantityType<Temperature>(value, unit);
        }

        public Unit<Temperature> getTemperatureUnit() {
            String scale = getStringParameter("scale", "");
            switch (scale) {
                case "celsius":
                    return SIUnits.CELSIUS;
                case "fahrenheit":
                    return ImperialUnits.FAHRENHEIT;
                default:
                    logger.debug("{}: no valid temperature scale parameter found, defaulting to: CELSIUS", nm());
                    return SIUnits.CELSIUS;
            }
        }
    }

    /**
     * Custom time quantity type message handler based of parameters
     */
    @NonNullByDefault
    public static class CustomTimeMsgHandler extends CustomMsgHandler {
        CustomTimeMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            Unit<Time> unit = getTimeUnit();
            return new QuantityType<Time>(value, unit);
        }

        public Unit<Time> getTimeUnit() {
            String scale = getStringParameter("scale", "");
            switch (scale) {
                case "hour":
                    return SmartHomeUnits.HOUR;
                case "minute":
                    return SmartHomeUnits.MINUTE;
                case "second":
                    return SmartHomeUnits.SECOND;
                default:
                    logger.debug("{}: no valid time scale parameter found, defaulting to: SECONDS", nm());
                    return SmartHomeUnits.SECOND;
            }
        }
    }

    /**
     * Last time message handler
     */
    @NonNullByDefault
    public static class LastTimeMsgHandler extends MessageHandler {
        LastTimeMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            Instant i = Instant.ofEpochMilli(msg.getTimestamp());
            ZonedDateTime timestamp = ZonedDateTime.ofInstant(i, ZoneId.systemDefault());
            ZonedDateTime lastTimestamp = getLastTimestamp();
            // set last time if not defined yet or message timestamp is greater than last value
            if (lastTimestamp == null || timestamp.compareTo(lastTimestamp) > 0) {
                feature.publish(new DateTimeType(timestamp), StateChangeType.ALWAYS);
            }
        }

        private @Nullable ZonedDateTime getLastTimestamp() {
            State state = feature.getLastState();
            return state != null ? ((DateTimeType) state).getZonedDateTime() : null;
        }
    }

    /**
     * Button press event message handler
     */
    @NonNullByDefault
    public static class ButtonPressEventHandler extends MessageHandler {
        ButtonPressEventHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            String event = getEvent(cmd1);
            if (event != null) {
                feature.triggerEvent(event);
                feature.pollRelatedDevices();
            }
        }

        private @Nullable String getEvent(byte cmd) {
            switch (cmd) {
                case 0x11:
                    return "PRESSED_ON";
                case 0x12:
                    return "DOUBLE_PRESSED_ON";
                case 0x13:
                    return "PRESSED_OFF";
                case 0x14:
                    return "DOUBLE_PRESSED_OFF";
                default:
                    logger.warn("{}: got unexpected command value: {}", nm(), ByteUtils.getHexString(cmd));
                    return null;
            }
        }
    }

    /**
     * Button hold event message handler
     */
    @NonNullByDefault
    public static class ButtonHoldEventHandler extends MessageHandler {
        ButtonHoldEventHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean isDuplicate(Msg msg) {
            // Disable duplicate elimination because
            // there are no cleanup or success messages for button hold event.
            return (false);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            try {
                byte cmd2 = msg.getByte("command2");
                String event = cmd2 == 0x01 ? "HELD_UP" : "HELD_DOWN";
                feature.triggerEvent(event);
            } catch (FieldException e) {
                logger.warn("{}: error parsing msg: {}", nm(), msg, e);
            }
        }
    }

    /**
     * Button release event message handler
     */
    @NonNullByDefault
    public static class ButtonReleaseEventHandler extends MessageHandler {
        ButtonReleaseEventHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean isDuplicate(Msg msg) {
            // Disable duplicate elimination because
            // there are no cleanup or success messages for button release event.
            return (false);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            feature.triggerEvent("RELEASED");
            feature.pollRelatedDevices();
        }
    }

    /**
     * Insteon engine reply message handler
     */
    @NonNullByDefault
    public static class InsteonEngineReplyHandler extends MessageHandler {
        InsteonEngineReplyHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            try {
                int version = msg.getInt("command2");
                InsteonDevice dev = feature.getDevice();
                // set device insteon engine
                dev.setInsteonEngine(InsteonEngine.valueOf(version));
                // continue device polling
                dev.doPoll(0L);
            } catch (FieldException e) {
                logger.warn("{}: error parsing msg: {}", nm(), msg, e);
            }
        }
    }

    /**
     * Product data message handler
     */
    @NonNullByDefault
    public static class ProductDataMsgHandler extends MessageHandler {
        ProductDataMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            try {
                String productKey = msg.getHexString("userData2", 3);
                String deviceCategory = msg.getHexString("userData5");
                String subCategory = msg.getHexString("userData6");
                int firmwareVersion = msg.getInt("userData7");

                ProductData productData = ProductDataLoader.instance().getProductData(deviceCategory, subCategory,
                        productKey.equals("0x000000") ? null : productKey);
                productData.setFirmwareVersion(firmwareVersion);
                if (logger.isTraceEnabled()) {
                    logger.trace("{}: got product data for {} as {}", nm(), feature.getDevice().getAddress(),
                            productData);
                }
                feature.getDevice().updateProductData(productData);
            } catch (FieldException e) {
                logger.warn("{}: error parsing msg: {}", nm(), msg, e);
            }
        }
    }

    /**
     * SET button pressed message handler
     */
    @NonNullByDefault
    public static class SetButtonPressedMsgHandler extends MessageHandler {
        SetButtonPressedMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            try {
                InsteonAddress addr = msg.getAddress("toAddress");
                String deviceCategory = ByteUtils.getHexString(addr.getHighByte());
                String subCategory = ByteUtils.getHexString(addr.getMiddleByte());
                int firmwareVersion = addr.getLowByte() & 0xFF;
                int hardwareVersion = msg.getInt("command2");

                ProductData productData = ProductDataLoader.instance().getProductData(deviceCategory, subCategory);
                productData.setFirmwareVersion(firmwareVersion);
                productData.setHardwareVersion(hardwareVersion);
                if (logger.isTraceEnabled()) {
                    logger.trace("{}: got product data for {} as {}", nm(), feature.getDevice().getAddress(),
                            productData);
                }
                feature.getDevice().updateProductData(productData);
            } catch (FieldException e) {
                logger.warn("{}: error parsing msg: {}", nm(), msg, e);
            }
        }
    }

    /**
     * On/Off abstract message handler
     */
    @NonNullByDefault
    public abstract static class OnOffMsgHandler extends MessageHandler {
        OnOffMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            String mode = getStringParameter("mode", "REGULAR");
            State state = getState(mode);
            if (logger.isDebugEnabled()) {
                logger.debug("{}: device {} changed to {} {}", nm(), feature.getDevice().getAddress(), state, mode);
            }
            feature.publish(state, StateChangeType.ALWAYS);
        }

        public abstract State getState(String mode);
    }

    /**
     * Dimmer on message handler
     */
    @NonNullByDefault
    public static class DimmerOnMsgHandler extends OnOffMsgHandler {
        DimmerOnMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public State getState(String mode) {
            switch (mode) {
                case "FAST":
                    // set to 100% for fast on change
                    return PercentType.HUNDRED;
                default:
                    // set to device on level if the current state not at that level already, defaulting to 100%
                    // this is due to subsequent dimmer on button press cycling between on level and 100%
                    State state = feature.getDevice().getLastState(ON_LEVEL_FEATURE);
                    return state != null && !state.equals(feature.getLastState()) ? state : PercentType.HUNDRED;
            }
        }
    }

    /**
     * Dimmer off message handler
     */
    @NonNullByDefault
    public static class DimmerOffMsgHandler extends OnOffMsgHandler {
        DimmerOffMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public State getState(String mode) {
            return PercentType.ZERO;
        }
    }

    /**
     * Dimmer request reply message handler
     */
    @NonNullByDefault
    public static class DimmerRequestReplyHandler extends CustomMsgHandler {
        DimmerRequestReplyHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            // trigger poll if is my brigthen/dim command reply message
            if ((cmd1 == 0x15 || cmd1 == 0x16) && feature.isMyDirectAckOrNack(msg)) {
                feature.triggerPoll(0L);
            } else {
                super.handleMessage(group, cmd1, msg);
            }
        }

        @Override
        public @Nullable State getState(int group, double value) {
            int level = (int) Math.round(value * 100 / 255.0);
            return new PercentType(level);
        }
    }

    /**
     * Switch on message handler
     */
    @NonNullByDefault
    public static class SwitchOnMsgHandler extends OnOffMsgHandler {
        SwitchOnMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public State getState(String mode) {
            return OnOffType.ON;
        }
    }

    /**
     * Switch off message handler
     */
    @NonNullByDefault
    public static class SwitchOffMsgHandler extends OnOffMsgHandler {
        SwitchOffMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public State getState(String mode) {
            return OnOffType.OFF;
        }
    }

    /**
     * Switch request reply message handler
     */
    @NonNullByDefault
    public static class SwitchRequestReplyHandler extends CustomMsgHandler {
        SwitchRequestReplyHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            int level = (int) value;
            State state = null;
            if (level == 0x00 || level == 0xFF) {
                state = level == 0xFF ? OnOffType.ON : OnOffType.OFF;
            } else {
                logger.warn("{}: ignoring unexpected level received {}", nm(), ByteUtils.getHexString(level));
            }
            return state;
        }
    }

    /**
     * Keypad button on message handler
     */
    @NonNullByDefault
    public static class KeypadButtonOnMsgHandler extends SwitchOnMsgHandler {
        KeypadButtonOnMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            super.handleMessage(group, cmd1, msg);
            // trigger poll to account for radio button group changes
            feature.triggerPoll(0L);
        }
    }

    /**
     * Keypad button off message handler
     */
    @NonNullByDefault
    public static class KeypadButtonOffMsgHandler extends SwitchOffMsgHandler {
        KeypadButtonOffMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            super.handleMessage(group, cmd1, msg);
            // trigger poll to account for radio button group changes
            feature.triggerPoll(0L);
        }
    }

    /**
     * Keypad bitmask message handler
     */
    @NonNullByDefault
    public static class KeypadBitmaskMsgHandler extends CustomBitmaskMsgHandler {
        KeypadBitmaskMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public int getBitNumber() {
            int bit = getGroup() - 1;
            // return bit if representing keypad button 2-8, otherwise -1
            return bit >= 1 && bit <= 7 ? bit : -1;
        }
    }

    /**
     * Keypad button reply message handler
     */
    @NonNullByDefault
    public static class KeypadButtonReplyHandler extends KeypadBitmaskMsgHandler {
        KeypadButtonReplyHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            // trigger poll if is my command reply message (0x2E)
            if (cmd1 == 0x2E && feature.isMyDirectAckOrNack(msg)) {
                feature.triggerPoll(0L);
            } else {
                super.handleMessage(group, cmd1, msg);
            }
        }
    }

    /**
     * Operating flags reply message handler
     */
    @NonNullByDefault
    public static class OpFlagsReplyHandler extends CustomBitmaskMsgHandler {
        OpFlagsReplyHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            // trigger poll if is my command reply message (0x20)
            if (cmd1 == 0x20 && feature.isMyDirectAckOrNack(msg)) {
                feature.triggerPoll(0L);
            } else {
                super.handleMessage(group, cmd1, msg);
            }
        }
    }

    /**
     * Keypad button config operating flag reply message handler
     */
    @NonNullByDefault
    public static class KeypadButtonConfigReplyHandler extends OpFlagsReplyHandler {
        KeypadButtonConfigReplyHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public State getBitFlagState(boolean is8Button) {
            // update device type based on button config
            updateDeviceType(is8Button ? "8" : "6");
            // return button config state
            return new StringType(is8Button ? "8-BUTTON" : "6-BUTTON");
        }

        private void updateDeviceType(String buttonConfig) {
            InsteonDevice dev = feature.getDevice();
            String curType = dev.getProductData().getDeviceType().getName();
            String newType = curType.replaceAll(".$", buttonConfig);
            if (!curType.equals(newType)) {
                DeviceType dt = DeviceTypeLoader.instance().getDeviceType(newType);
                if (dt == null) {
                    logger.warn("{}: unknown device type {}", nm(), newType);
                } else {
                    logger.trace("{}: updating to device type {} for {}", nm(), dt.getName(), dev.getAddress());
                    // set product data device type
                    dev.getProductData().setDeviceType(dt);
                    // reset device features
                    dev.resetFeatures();
                    // poll updated device
                    dev.doPoll(0L);
                }
            }
        }
    }

    /**
     * Ramp rate extended status message handler
     */
    @NonNullByDefault
    public static class RampRateMsgHandler extends CustomMsgHandler {
        RampRateMsgHandler(DeviceFeature f) {
            super(f);
        }

        private static final double[] rampRateTimes = {
            0.1, 0.2, 0.3, 0.5, 2, 4.5, 6.5, 8.5, 19, 21.5, 23.5, 26, 28, 30, 32, 34,
            38.5, 43, 47, 60, 90, 120, 150, 180, 210, 240, 270, 300, 360, 420, 480
        };

        @Override
        public @Nullable State getState(int group, double value) {
            double rateTime = getRateTime((int) value);
            return (rateTime >= 0) ? new QuantityType<Time>(rateTime, SmartHomeUnits.SECOND) : UnDefType.UNDEF;
        }

        private double getRateTime(int value) {
            switch (value) {
                case 0x00:
                    return 2; // 0x00 setting defaults to 2s ramp rate, based on developer documentation
                default:
                    int index = rampRateTimes.length - value;
                    if (index < 0) {
                       logger.warn("{}: got unexpected ramp rate message: {}", nm(), ByteUtils.getHexString(value));
                       return -1;
                    }
                    return rampRateTimes[index];
            }
        }
    }

    /**
     * Sensor abstract message handler
     */
    @NonNullByDefault
    public abstract static class SensorMsgHandler extends CustomMsgHandler {
        SensorMsgHandler(DeviceFeature f) {
            super(f);
            setStateChangeType(StateChangeType.ALWAYS);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            super.handleMessage(group, cmd1, msg);
            // poll battery powered sensor device while awake
            InsteonDevice dev = feature.getDevice();
            if (dev.isBatteryPowered()) {
              // set delay to 1500ms to allow all-link cleanup msg to be processed beforehand
              // only on non-replayed message, otherwise no delay
                long delay = msg.isReplayed() ? 0L : 1500L;
                dev.doPoll(delay);
            }
            // poll related devices
            feature.pollRelatedDevices();
        }
    }


    /**
     * Contact open message handler
     */
    @NonNullByDefault
    public static class ContactOpenMsgHandler extends SensorMsgHandler {
        ContactOpenMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            return OpenClosedType.OPEN;
        }
    }

    /**
     * Contact closed message handler
     */
    @NonNullByDefault
    public static class ContactClosedMsgHandler extends SensorMsgHandler {
        ContactClosedMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            return OpenClosedType.CLOSED;
        }
    }

    /**
     * Contact request reply message handler
     */
    @NonNullByDefault
    public static class ContactRequestReplyHandler extends CustomMsgHandler {
        ContactRequestReplyHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            return value == 0 ? OpenClosedType.OPEN : OpenClosedType.CLOSED;
        }
    }

    /**
     * Wireless sensor contact open message handler
     */
    @NonNullByDefault
    public static class WirelessSensorContactOpenMsgHandler extends SensorMsgHandler {
        WirelessSensorContactOpenMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            // return open state if group parameter configured
            if (getGroup() != -1) {
                return OpenClosedType.OPEN;
            }
            switch (group) {
                case 1: // open event
                case 4: // heartbeat
                    return OpenClosedType.OPEN;
                default: // ignore
                    return null;
            }
        }
    }

    /**
     * Wireless sensor contact closed message handler
     */
    @NonNullByDefault
    public static class WirelessSensorContactClosedMsgHandler extends SensorMsgHandler {
        WirelessSensorContactClosedMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            // return closed state if group parameter configured
            if (getGroup() != -1) {
                return OpenClosedType.CLOSED;
            }
            switch (group) {
                case 1: // closed event
                case 4: // heartbeat
                    return OpenClosedType.CLOSED;
                default: // ignore
                    return null;
            }
        }
    }

    /**
     * Wireless sensor state on message handler
     */
    @NonNullByDefault
    public static class WirelessSensorStateOnMsgHandler extends SensorMsgHandler {
        WirelessSensorStateOnMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            // return on state if group parameter configured
            if (getGroup() != -1) {
                return OnOffType.ON;
            }
            switch (group) {
                case 1: // on event
                case 4: // heartbeat
                    return OnOffType.ON;
                default: // ignore
                    return null;
            }
        }
    }

    /**
     * Wireless sensor state off message handler
     */
    @NonNullByDefault
    public static class WirelessSensorStateOffMsgHandler extends SensorMsgHandler {
        WirelessSensorStateOffMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            // return off state if group parameter configured
            if (getGroup() != -1) {
                return OnOffType.OFF;
            }
            switch (group) {
                case 1: // off event
                case 4: // heartbeat
                    return OnOffType.OFF;
                default: // ignore
                    return null;
            }
        }
    }

    /**
     * Leak sensor state on message handler
     */
    @NonNullByDefault
    public static class LeakSensorStateOnMsgHandler extends SensorMsgHandler {
        LeakSensorStateOnMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            switch (group) {
                case 1: // dry event
                case 4: // heartbeat (dry)
                    return OnOffType.OFF;
                case 2: // wet event
                    return OnOffType.ON;
                default: // ignore
                    return null;
            }
        }
    }

    /**
     * Leak sensor state off message handler
     */
    @NonNullByDefault
    public static class LeakSensorStateOffMsgHandler extends SensorMsgHandler {
        LeakSensorStateOffMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            switch (group) {
                case 4: // heartbeat (wet)
                    return OnOffType.ON;
                default: // ignore
                    return null;
            }
        }
    }

    /**
     * Motion sensor 2 temperature message handler
     */
    @NonNullByDefault
    public static class MotionSensor2TemperatureMsgHandler extends CustomMsgHandler {
        MotionSensor2TemperatureMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            boolean isBatteryPowered = feature.getDevice().isBatteryPowered();
            // temperature (°F) = 0.73 * value - 20.53 (battery powered); 0.72 * value - 24.61 (usb powered)
            double temperature = isBatteryPowered ? 0.73 * value - 20.53 : 0.72 * value - 24.61;
            return new QuantityType<Temperature>(temperature, ImperialUnits.FAHRENHEIT);
        }
    }

    /**
     * Motion sensor 2 battery powered reply message handler
     */
    @NonNullByDefault
    public static class MotionSensor2BatteryPoweredReplyHandler extends CustomMsgHandler {
        MotionSensor2BatteryPoweredReplyHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            // stage flag bit 1 = USB Powered
            boolean isBatteryPowered = !BitwiseUtils.isBitFlagSet((int) value, 1);
            // update device based on battery powered flag
            updateDeviceFlag(isBatteryPowered);
            // return battery powered state
            return isBatteryPowered ? OnOffType.ON : OnOffType.OFF;
        }

        private void updateDeviceFlag(boolean isBatteryPowered) {
            InsteonDevice dev = feature.getDevice();
            // update device batteryPowered flag
            dev.setFlag("batteryPowered", isBatteryPowered);
            // stop device polling if battery powered, otherwise start it
            if (isBatteryPowered) {
                dev.stopPolling();
            } else {
                dev.startPolling();
            }
        }
    }

    /**
     * Smoke sensor alarm message handler
     */
    @NonNullByDefault
    public static class SmokeSensorAlarmMsgHandler extends SensorMsgHandler {
        SmokeSensorAlarmMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            int alarm = getIntParameter("alarm", -1);
            State state = null;
            if (group == alarm) {
                state = OnOffType.ON;  // alarm event
            } else if (group == 0x05) {
                state = OnOffType.OFF; // clear event
            }
            return state;
        }
    }

    /**
     * FanLinc fan mode reply message handler
     */
    @NonNullByDefault
    public static class FanLincFanReplyHandler extends CustomMsgHandler {
        FanLincFanReplyHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            String mode = getMode((int) value);
            return (mode != null) ? new StringType(mode) : UnDefType.UNDEF;
        }

        private @Nullable String getMode(int value) {
            switch (value) {
                case 0x00:
                    return "OFF";
                case 0x55:
                    return "LOW";
                case 0xAA:
                    return "MEDIUM";
                case 0xFF:
                    return "HIGH";
                default:
                    logger.warn("{}: got unexpected fan mode reply value: {}", nm(), ByteUtils.getHexString(value));
                    return null;
            }
        }
    }

    /**
     * I/O linc momentary duration message handler
     */
    @NonNullByDefault
    public static class IOLincMomentaryDurationMsgHandler extends CustomMsgHandler {
        IOLincMomentaryDurationMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            int duration = getDuration((int) value);
            return new QuantityType<Time>(duration, SmartHomeUnits.SECOND);
        }

        private int getDuration(int value) {
            int prescaler = value >> 8; // high byte
            int delay = value & 0xFF; // low byte
            if (delay == 0) {
                delay = 255;
            }
            return delay * prescaler / 10;
        }
    }

    /**
     * I/O linc relay mode reply message handler
     */
    @NonNullByDefault
    public static class IOLincRelayModeReplyHandler extends CustomMsgHandler {
        IOLincRelayModeReplyHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            // trigger poll if is my command reply message (0x20)
            if (cmd1 == 0x20 && feature.isMyDirectAckOrNack(msg)) {
                feature.triggerPoll(5000L); // 5000ms delay to allow all op flag commands to be processed
            } else {
                super.handleMessage(group, cmd1, msg);
            }
        }

        @Override
        public @Nullable State getState(int group, double value) {
            String mode;
            if (!BitwiseUtils.isBitFlagSet((int) value, 3)) {
                // set mode to latching, when momentary mode op flag (3) is off
                mode = "LATCHING";
            } else if (BitwiseUtils.isBitFlagSet((int) value, 7)) {
                // set mode to momentary c, when momentary sensor follow op flag (7) is on
                mode = "MOMENTARY_C";
            } else if (BitwiseUtils.isBitFlagSet((int) value, 4)) {
                // set mode to momentary b, when momentary trigger on/off op flag (4) is on
                mode = "MOMENTARY_B";
            } else {
                // set mode to momentary a, otherwise
                mode = "MOMENTARY_A";
            }
            return new StringType(mode);
        }
    }

    /**
     *  Outlet top reply message handler
     *
     *  0x00 = Both Outlets Off
     *  0x01 = Only Top Outlet On
     *  0x02 = Only Bottom Outlet On
     *  0x03 = Both Outlets On
     */
    @NonNullByDefault
    public static class OutletTopReplyHandler extends CustomMsgHandler {
        OutletTopReplyHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            return value == 0x01 || value == 0x03 ? OnOffType.ON : OnOffType.OFF;
        }
    }

    /**
     * Outlet bottom reply message handler
     *
     *  0x00 = Both Outlets Off
     *  0x01 = Only Top Outlet On
     *  0x02 = Only Bottom Outlet On
     *  0x03 = Both Outlets On
     */
    @NonNullByDefault
    public static class OutletBottomReplyHandler extends CustomMsgHandler {
        OutletBottomReplyHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            return value == 0x02 || value == 0x03 ? OnOffType.ON : OnOffType.OFF;
        }
    }

    /**
     * Power meter kWh message handler
     */
    @NonNullByDefault
    public static class PowerMeterKWhMsgHandler extends CustomMsgHandler {
        PowerMeterKWhMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            BigDecimal kWh = getKWh((int) value);
            return new QuantityType<Energy>(kWh, SmartHomeUnits.KILOWATT_HOUR);
        }

        private BigDecimal getKWh(int energy) {
            BigDecimal kWh = BigDecimal.ZERO;
            int highByte = energy >> 24;
            if (highByte < 254) {
                kWh = new BigDecimal(energy * 65535.0 / (1000 * 60 * 60 * 60)).setScale(4, RoundingMode.HALF_UP);
            }
            return kWh;
        }
    }

    /**
     * Power meter watts message handler
     */
    @NonNullByDefault
    public static class PowerMeterWattsMsgHandler extends CustomMsgHandler {
        PowerMeterWattsMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            int watts = getWatts((int) value);
            return new QuantityType<Power>(watts, SmartHomeUnits.WATT);
        }

        private int getWatts(int watts) {
            if (watts > 32767) {
                watts -= 65535;
            }
            return watts;
        }
    }

    /**
     * Thermostat fan mode message handler
     */
    @NonNullByDefault
    public static class ThermostatFanModeMsgHandler extends CustomMsgHandler {
        ThermostatFanModeMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            String mode = getMode((int) value);
            return (mode != null) ? new StringType(mode) : UnDefType.UNDEF;
        }

        private @Nullable String getMode(int value) {
            switch (value) {
                case 0:
                    return "AUTO";
                case 1:
                    return "ON";
                default:
                    logger.warn("{}: got unexpected fan mode value: {}", nm(), value);
                    return null;
            }
        }
    }

    /**
     * Thermostat fan mode reply message handler
     */
    @NonNullByDefault
    public static class ThermostatFanModeReplyHandler extends CustomMsgHandler {
        ThermostatFanModeReplyHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            String mode = getMode((int) value);
            return (mode != null) ? new StringType(mode) : UnDefType.UNDEF;
        }

        private @Nullable String getMode(int value) {
            switch (value) {
                case 0x08:
                    return "AUTO";
                case 0x07:
                    return "ON";
                default:
                    logger.warn("{}: got unexpected fan mode reply value: {}", nm(), ByteUtils.getHexString(value));
                    return null;
            }
        }
    }

    /**
     * Termostat system mode message handler
     */
    @NonNullByDefault
    public static class ThermostatSystemModeMsgHandler extends CustomMsgHandler {
        ThermostatSystemModeMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            String mode = getMode((int) value);
            return (mode != null) ? new StringType(mode) : UnDefType.UNDEF;
        }

        private @Nullable String getMode(int value) {
            switch (value) {
                case 0:
                    return "OFF";
                case 1:
                    return "AUTO";
                case 2:
                    return "HEAT";
                case 3:
                    return "COOL";
                case 4:
                    return "PROGRAM";
                default:
                    logger.warn("{}: got unexpected system mode value: {}", nm(), value);
                    return null;
            }
        }
    }

    /**
     * Thermostat system mode reply message handler
     */
    @NonNullByDefault
    public static class ThermostatSystemModeReplyHandler extends CustomMsgHandler {
        ThermostatSystemModeReplyHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            String mode = getMode((int) value);
            return (mode != null) ? new StringType(mode) : UnDefType.UNDEF;
        }

        private @Nullable String getMode(int value) {
            switch (value) {
                case 0x09:
                    return "OFF";
                case 0x04:
                    return "HEAT";
                case 0x05:
                    return "COOL";
                case 0x06:
                    return "AUTO";
                case 0x0A:
                    return "PROGRAM";
                default:
                    logger.warn("{}: got unexpected system mode reply value: {}", nm(), ByteUtils.getHexString(value));
                    return null;
            }
        }
    }

    /**
     * Venstar thermostat system mode message handler
     */
    @NonNullByDefault
    public static class VenstarSystemModeMsgHandler extends CustomMsgHandler {
        VenstarSystemModeMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            String mode = getMode((int) value);
            return (mode != null) ? new StringType(mode) : UnDefType.UNDEF;
        }

        private @Nullable String getMode(int value) {
            switch (value) {
                case 0:
                    return "OFF";
                case 1:
                    return "HEAT";
                case 2:
                    return "COOL";
                case 3:
                    return "AUTO";
                case 4:
                    return "PROGRAM_AUTO";
                case 5:
                    return "PROGRAM_HEAT";
                case 6:
                    return "PROGRAM_COOL";
                default:
                    logger.warn("{}: got unexpected system mode value: {}", nm(), value);
                    return null;
            }
        }
    }

    /**
     * Venstar thermostat system mode message handler
     */
    @NonNullByDefault
    public static class VenstarSystemModeReplyHandler extends CustomMsgHandler {
        VenstarSystemModeReplyHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            String mode = getMode((int) value);
            return (mode != null) ? new StringType(mode) : UnDefType.UNDEF;
        }

        private @Nullable String getMode(int value) {
            switch (value) {
                case 0x09:
                    return "OFF";
                case 0x04:
                    return "HEAT";
                case 0x05:
                    return "COOL";
                case 0x06:
                    return "AUTO";
                case 0x0A:
                    return "PROGRAM_HEAT";
                case 0x0B:
                    return "PROGRAM_COOL";
                case 0x0C:
                    return "PROGRAM_AUTO";
                default:
                    logger.warn("{}: got unexpected system mode reply value: {}", nm(), ByteUtils.getHexString(value));
                    return null;
            }
        }
    }

    /**
     * Thermostat system status message handler
     */
    @NonNullByDefault
    public static class ThermostatSystemStateMsgHandler extends CustomMsgHandler {
        ThermostatSystemStateMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            String status = getStatus((int) value);
            return (status != null) ? new StringType(status) : UnDefType.UNDEF;
        }

        private @Nullable String getStatus(int value) {
            switch (value) {
                case 0:
                    return "OFF";
                case 1:
                    return "COOLING";
                case 2:
                    return "HEATING";
                case 3:
                    return "DEHUMIDIFYING";
                case 4:
                    return "HUMIDIFYING";
                default:
                    logger.warn("{}: got unexpected system status value: {}", nm(), value);
                    return null;
            }
        }
    }

    /**
     * Venstar thermostat temperature message handler
     */
    @NonNullByDefault
    public static class VenstarTemperatureMsgHandler extends CustomTemperatureMsgHandler {
        VenstarTemperatureMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public Unit<Temperature> getTemperatureUnit() {
            // use temperature format last state to determine temperature unit, defaulting to fahrenheit
            State state = feature.getDevice().getLastState(TEMPERATURE_FORMAT_FEATURE);
            String format = state == null ? "FAHRENHEIT" : ((StringType) state).toString();
            switch (format) {
                case "CELSIUS":
                    return SIUnits.CELSIUS;
                case "FAHRENHEIT":
                    return ImperialUnits.FAHRENHEIT;
                default:
                    logger.debug("{}: unable to determine temperature scale, defaulting to: FAHRENHEIT", nm());
                    return ImperialUnits.FAHRENHEIT;
            }
        }
    }

    /**
     * Thermostat temperature format message handler
     */
    @NonNullByDefault
    public static class ThermostatTemperatureFormatMsgHandler extends CustomBitmaskMsgHandler {
        ThermostatTemperatureFormatMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public State getBitFlagState(boolean isSet) {
            String format = isSet ? "CELSIUS" : "FAHRENHEIT";
            return new StringType(format);
        }
    }

    /**
     * Venstar thermostat temperature format message handler
     */
    @NonNullByDefault
    public static class VenstarTemperatureFormatMsgHandler extends CustomMsgHandler {
        VenstarTemperatureFormatMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public @Nullable State getState(int group, double value) {
            String format = (int) value == 0x01 ? "CELSIUS" : "FAHRENHEIT";
            return new StringType(format);
        }
    }

    /**
     * Thermostat time format message handler
     */
    @NonNullByDefault
    public static class ThermostatTimeFormatMsgHandler extends CustomBitmaskMsgHandler {
        ThermostatTimeFormatMsgHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public State getBitFlagState(boolean isSet) {
            String format = isSet ? "24H" : "12H";
            return new StringType(format);
        }
    }


    /**
     * Process X10 messages that are generated when another controller
     * changes the state of an X10 device.
     */
    @NonNullByDefault
    public static class X10OnHandler extends MessageHandler {
        X10OnHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            if (logger.isDebugEnabled()) {
                logger.debug("{}: device {} changed to ON", nm(), feature.getDevice().getAddress());
            }
            feature.publish(OnOffType.ON, StateChangeType.ALWAYS);
        }
    }

    @NonNullByDefault
    public static class X10OffHandler extends MessageHandler {
        X10OffHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            if (logger.isDebugEnabled()) {
                logger.debug("{}: device {} changed to OFF", nm(), feature.getDevice().getAddress());
            }
            feature.publish(OnOffType.OFF, StateChangeType.ALWAYS);
        }
    }

    @NonNullByDefault
    public static class X10BrightHandler extends MessageHandler {
        X10BrightHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            if (logger.isDebugEnabled()) {
                logger.debug("{}: ignoring brighten message for device {}", nm(), feature.getDevice().getAddress());
            }
        }
    }

    @NonNullByDefault
    public static class X10DimHandler extends MessageHandler {
        X10DimHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            if (logger.isDebugEnabled()) {
                logger.debug("{}: ignoring dim message for device {}", nm(), feature.getDevice().getAddress());
            }
        }
    }

    @NonNullByDefault
    public static class X10OpenHandler extends MessageHandler {
        X10OpenHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            if (logger.isDebugEnabled()) {
                logger.debug("{}: device {} changed to OPEN", nm(), feature.getDevice().getAddress());
            }
            feature.publish(OpenClosedType.OPEN, StateChangeType.ALWAYS);
        }
    }

    @NonNullByDefault
    public static class X10ClosedHandler extends MessageHandler {
        X10ClosedHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleMessage(int group, byte cmd1, Msg msg) {
            if (logger.isDebugEnabled()) {
                logger.debug("{}: device {} changed to CLOSED", nm(), feature.getDevice().getAddress());
            }
            feature.publish(OpenClosedType.CLOSED, StateChangeType.ALWAYS);
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
    public static @Nullable <T extends MessageHandler> T makeHandler(String name,
            Map<String, @Nullable String> params, DeviceFeature f) {
        String cname = MessageHandler.class.getName() + "$" + name;
        try {
            Class<?> c = Class.forName(cname);
            @SuppressWarnings("unchecked")
            Class<? extends T> dc = (Class<? extends T>) c;
            T mh = dc.getDeclaredConstructor(DeviceFeature.class).newInstance(f);
            mh.addParameters(params);
            return mh;
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | IllegalArgumentException
                | InvocationTargetException | NoSuchMethodException | SecurityException e) {
            logger.warn("error trying to create message handler: {}", name, e);
        }
        return null;
    }
}
