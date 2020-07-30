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
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.measure.Unit;
import javax.measure.quantity.Dimensionless;
import javax.measure.quantity.Temperature;
import javax.measure.quantity.Time;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.IncreaseDecreaseType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.types.StopMoveType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.library.types.UpDownType;
import org.eclipse.smarthome.core.library.unit.ImperialUnits;
import org.eclipse.smarthome.core.library.unit.SmartHomeUnits;
import org.eclipse.smarthome.core.library.unit.SIUnits;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.State;
import org.openhab.binding.insteon.internal.config.InsteonChannelConfiguration;
import org.openhab.binding.insteon.internal.device.DeviceFeatureListener.StateChangeType;
import org.openhab.binding.insteon.internal.message.FieldException;
import org.openhab.binding.insteon.internal.message.InvalidMessageTypeException;
import org.openhab.binding.insteon.internal.message.Msg;
import org.openhab.binding.insteon.internal.utils.BitwiseUtils;
import org.openhab.binding.insteon.internal.utils.ByteUtils;
import org.openhab.binding.insteon.internal.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A command handler translates an openHAB command into a insteon message
 *
 * @author Daniel Pfrommer - Initial contribution
 * @author Bernd Pfrommer - openHAB 1 insteonplm binding
 * @author Rob Nielsen - Port to openHAB 2 insteon binding
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
@SuppressWarnings("null")
public abstract class CommandHandler extends FeatureBaseHandler {
    private static final Logger logger = LoggerFactory.getLogger(CommandHandler.class);

    /**
     * Constructor
     *
     * @param f The DeviceFeature for which this command was intended.
     *            The openHAB commands are issued on an openhab item. The .items files bind
     *            an openHAB item to a DeviceFeature.
     */
    CommandHandler(DeviceFeature f) {
        super(f);
    }

    /**
     * Returns if handler can handle the openHAB command received
     *
     * @param  cmd the openhab command received
     * @return true if can handle
     */
    public abstract boolean canHandle(Command cmd);

    /**
     * Implements what to do when an openHAB command is received
     *
     * @param config the configuration for the item that generated the command
     * @param cmd the openhab command issued
     * @param device the Insteon device to which this command applies
     */
    public abstract void handleCommand(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev);

    //
    //
    // ---------------- the various command handlers start here -------------------
    //
    //

    /**
     * Warn command handler
     */
    @NonNullByDefault
    public static class WarnCommandHandler extends CommandHandler {
        WarnCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return true;
        }

        @Override
        public void handleCommand(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev) {
            logger.warn("{}: command {} is not implemented yet!", nm(), cmd);
        }
    }

    /**
     * No-op command handler
     */
    @NonNullByDefault
    public static class NoOpCommandHandler extends CommandHandler {
        NoOpCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return true;
        }

        @Override
        public void handleCommand(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev) {
            // do nothing, not even log
        }
    }

    /**
     * Custom abstract command handler based of parameters
     */
    @NonNullByDefault
    public abstract static class CustomCommandHandler extends CommandHandler {
        CustomCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleCommand(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev) {
            int cmd1 = getIntParameter("cmd1", -1);
            int cmd2 = getIntParameter("cmd2", 0);
            int ext = getIntParameter("ext", 0);
            if (cmd1 == -1) {
                logger.warn("{}: handler misconfigured, no cmd1 parameter specified", nm());
                return;
            }
            // determine data field based on parameter, default to cmd2 if is standard message
            String field = getStringParameter("field", ext == 0 ? "command2" : "");
            if (field == "") {
                logger.warn("{}: handler misconfigured, no field parameter specified", nm());
                return;
            }
            // determine cmd value and apply factor ratio based of parameters
            int value = (int) Math.round(getValue(cmd) * getIntParameter("factor", 1));
            if (value == -1) {
                logger.debug("{}: unable to determine command value, ignoring request", nm());
                return;
            }
            try {
                Msg m = null;
                if (ext == 1 || ext == 2) {
                    // set userData1 to d1 parameter if defined, fallback to group parameter
                    byte[] data = { (byte) getIntParameter("d1", getIntParameter("group", 0)),
                            (byte) getIntParameter("d2", 0), (byte) getIntParameter("d3", 0) };
                    m = dev.makeExtendedMessage((byte) 0x1F, (byte) cmd1, (byte) cmd2, data);
                } else {
                    m = dev.makeStandardMessage((byte) 0x0F, (byte) cmd1, (byte) cmd2);
                }
                // set field to clamped byte-size value
                m.setByte(field, (byte) Math.min(value, 0xFF));
                // update crc based on message type and device Insteon engine checksum support
                if (ext == 1 && dev.getInsteonEngine().supportsChecksum()) {
                    m.setCRC();
                } else if (ext == 2) {
                    m.setCRC2();
                }
                dev.enqueueRequest(m, feature);
                if (logger.isDebugEnabled()) {
                    logger.debug("{}: sent {} {} request to {}", nm(), feature.getName(),
                            ByteUtils.getHexString(value), dev.getAddress());
                }
            } catch (FieldException e) {
                logger.warn("{}: command send message creation error ", nm(), e);
            } catch (InvalidMessageTypeException e) {
                logger.warn("{}: invalid message: ", nm(), e);
            }
        }

        public abstract double getValue(Command cmd);
    }

    /**
     * Custom bitmask command handler based of parameters
     */
    @NonNullByDefault
    public static class CustomBitmaskCommandHandler extends CustomCommandHandler {
        CustomBitmaskCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof OnOffType;
        }

        @Override
        public double getValue(Command cmd) {
            return getBitmask(cmd);
        }

        public int getBitNumber() {
            return getIntParameter("bit", -1);
        }

        public boolean shouldSetBit(Command cmd) {
            return cmd == OnOffType.ON ^ getBooleanParameter("inverted", false);
        }

        public int getBitmask(Command cmd) {
            // get bit number based on parameter
            int bit = getBitNumber();
            // get last bitmask message value received by this feature
            int bitmask = feature.getIntLastMsgValue(-1);
            // update last bitmask value specific bit based on cmd state, if defined and bit number valid (1-7)
            if (bit < 1 || bit > 7) {
                logger.warn("{}: incorrect bit number {} for feature {}", nm(), bit, feature.getName());
            } else if (bitmask == -1) {
                logger.debug("{}: unable to determine last bit mask for feature {}", nm(), feature.getName());
            } else {
                boolean shouldSetBit = shouldSetBit(cmd);
                if (logger.isTraceEnabled()) {
                    logger.trace("{}: bitmask:{} bit:{} set:{}", nm(), ByteUtils.getBinaryString(bitmask), bit,
                            shouldSetBit);
                }
                return shouldSetBit ? BitwiseUtils.setBitFlag(bitmask, bit) : BitwiseUtils.clearBitFlag(bitmask, bit);
            }
            return -1;
        }
    }


    /**
     * Custom on/off type command handler based of parameters
     */
    @NonNullByDefault
    public static class CustomOnOffCommandHandler extends CustomCommandHandler {
        CustomOnOffCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof OnOffType;
        }

        @Override
        public double getValue(Command cmd) {
            return cmd == OnOffType.ON ? getIntParameter("on", 0xFF) : getIntParameter("off", 0x00);
        }
    }


    /**
     * Custom decimal type command handler based of parameters
     */
    @NonNullByDefault
    public static class CustomDecimalCommandHandler extends CustomCommandHandler {
        CustomDecimalCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof DecimalType;
        }

        @Override
        public double getValue(Command cmd) {
            return ((DecimalType) cmd).doubleValue();
        }
    }

    /**
     * Custom percent type command handler based of parameters
     */
    @NonNullByDefault
    public static class CustomPercentCommandHandler extends CustomCommandHandler {
        CustomPercentCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof PercentType;
        }

        @Override
        public double getValue(Command cmd) {
            int minValue = getIntParameter("min", 0x00);
            int maxValue = getIntParameter("max", 0xFF);
            double value = ((PercentType) cmd).doubleValue();
            return Math.round(value * (maxValue - minValue) / 100.0) + minValue;
        }
    }

    /**
     * Custom dimensionless quantity type command handler based of parameters
     */
    @NonNullByDefault
    public static class CustomDimensionlessCommandHandler extends CustomCommandHandler {
        CustomDimensionlessCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof QuantityType;
        }

        @Override
        @SuppressWarnings("unchecked")
        public double getValue(Command cmd) {
          int minValue = getIntParameter("min", 0);
          int maxValue = getIntParameter("max", 100);
          double value = ((QuantityType<Dimensionless>) cmd).doubleValue();
          return Math.round(value * (maxValue - minValue) / 100.0) + minValue;
        }
    }

    /**
     * Custom temperature quantity type command handler based of parameters
     */
    @NonNullByDefault
    public static class CustomTemperatureCommandHandler extends CustomCommandHandler {
        CustomTemperatureCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof QuantityType;
        }

        @Override
        @SuppressWarnings("unchecked")
        public double getValue(Command cmd) {
            QuantityType<Temperature> temperature = (QuantityType<Temperature>) cmd;
            Unit<Temperature> unit = getTemperatureUnit();
            double value = temperature.toUnit(unit).doubleValue();
            double increment = unit == SIUnits.CELSIUS ? 0.5 : 1;
            return Math.round(value / increment) * increment; // round in increment based on temperature unit
        }

        private Unit<Temperature> getTemperatureUnit() {
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
     * Custom time quantity type command handler based of parameters
     */
    @NonNullByDefault
    public static class CustomTimeCommandHandler extends CustomCommandHandler {
        CustomTimeCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof QuantityType;
        }

        @Override
        @SuppressWarnings("unchecked")
        public double getValue(Command cmd) {
            QuantityType<Time> time = (QuantityType<Time>) cmd;
            Unit<Time> unit = getTimeUnit();
            return time.toUnit(unit).doubleValue();
        }

        private Unit<Time> getTimeUnit() {
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
     * Generic on/off command handler
     */
    @NonNullByDefault
    public static class OnOffCommandHandler extends CommandHandler {
        OnOffCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof OnOffType;
        }

        @Override
        public void handleCommand(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev) {
            try {
                int cmd1 = getCommandCode(conf, cmd);
                int level = getLevel(conf, cmd);
                int group = getGroup(conf);
                if (level != -1) {
                    Msg m = dev.makeStandardMessage((byte) 0x0F, (byte) cmd1, (byte) level, group);
                    dev.enqueueRequest(m, feature);
                    if (m.isBroadcast()) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("{}: sent broadcast {} request to group {}", nm(), cmd, group);
                        }
                        updateRelatedStates(conf, cmd, true);
                    } else {
                        if (logger.isDebugEnabled()) {
                            logger.debug("{}: sent {} request to {}", nm(), cmd, dev.getAddress());
                        }
                        updateRelatedStates(conf, cmd, false);
                    }
                }
            } catch (InvalidMessageTypeException e) {
                logger.warn("{}: invalid message: ", nm(), e);
            } catch (FieldException e) {
                logger.warn("{}: command send message creation error ", nm(), e);
            }
        }

        public int getCommandCode(InsteonChannelConfiguration conf, Command cmd) {
            return cmd == OnOffType.ON ? getIntParameter("on", 0x11) : getIntParameter("off", 0x13);
        }

        public int getLevel(InsteonChannelConfiguration conf, Command cmd) {
            return cmd == OnOffType.ON && getGroup(conf) == -1 ? 0xFF : 0x00; // not parsed in broadcast request
        }

        public int getGroup(InsteonChannelConfiguration conf) {
            return -1;
        }

        public void updateRelatedStates(InsteonChannelConfiguration conf, Command cmd, boolean isBroadcast) {
            if (isBroadcast) {
                // poll this feature with delay to account for local changes
                feature.triggerPoll(2000L);
                // poll related devices for triggered channel
                feature.pollRelatedDevices(conf.getChannelName());
            } else {
                // adjust related devices separately
                //feature.adjustRelatedDevices(conf.getChannelName(), cmd);
            }
        }
    }

    /**
     * Generic secondary functionality on/off command handler
     */
    @NonNullByDefault
    public static class SecondaryOnOffCommandHandler extends OnOffCommandHandler {
        SecondaryOnOffCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleCommand(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev) {
            try {
                int userData1 = getIntParameter("d1", -1);
                // handle command only if d1 parameter defined in command handler
                if (userData1 != -1) {
                    int cmd1 = getCommandCode(conf, cmd);
                    int level = getLevel(conf, cmd);
                    byte[] data = { (byte) userData1, (byte) 0x00, (byte) 0x00 };
                    Msg m = dev.makeExtendedMessage((byte) 0x1F, (byte) cmd1, (byte) level, data);
                    dev.enqueueRequest(m, feature);
                    if (logger.isDebugEnabled()) {
                        logger.debug("{}: sent {} request to {}", nm(), cmd, dev.getAddress());
                    }
                } else {
                    logger.warn("{}: no d1 parameter specified in command handler", nm());
                }
            } catch (InvalidMessageTypeException e) {
                logger.warn("{}: invalid message: ", nm(), e);
            } catch (FieldException e) {
                logger.warn("{}: command send message creation error ", nm(), e);
            }
        }
    }

    /**
     * Dimmer on/off command handler
     */
    @NonNullByDefault
    public static class DimmerOnOffCommandHandler extends OnOffCommandHandler {
        DimmerOnOffCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public int getCommandCode(InsteonChannelConfiguration conf, Command cmd) {
            return cmd == OnOffType.ON ? 0x11 : 0x13;
        }

        @Override
        public int getLevel(InsteonChannelConfiguration conf, Command cmd) {
            return cmd == OnOffType.ON && getGroup(conf) == -1 ? getOnLevel() : 0x00; // not parsed in broadcast request
        }

        @Override
        public int getGroup(InsteonChannelConfiguration conf) {
            return conf.getBroadcastGroup();
        }

        @Override
        public void updateRelatedStates(InsteonChannelConfiguration conf, Command cmd, boolean isBroadcast) {
            super.updateRelatedStates(conf, cmd, isBroadcast);
            // update dimmer state since set not to be automatically updated by the framework
            updateState(conf, cmd);
        }

        public void updateState(InsteonChannelConfiguration conf, Command cmd) {
            PercentType state = cmd == OnOffType.ON ? getOnLevelState() : PercentType.ZERO;
            feature.publish(state, StateChangeType.ALWAYS);
        }

        private int getOnLevel() {
            int value = getOnLevelState().intValue();
            int level = (int) Math.ceil((value * 255.0) / 100); // round up
            if (logger.isTraceEnabled()) {
                logger.trace("{}: using on level value of {}%", nm(), value);
            }
            return level;
        }

        private PercentType getOnLevelState() {
            PercentType state = (PercentType) feature.getDevice().getLastState(ON_LEVEL_FEATURE);
            return state != null ? state : PercentType.HUNDRED;
        }
    }

    /**
     * Dimmer percent command handler
     */
    @NonNullByDefault
    public static class DimmerPercentCommandHandler extends DimmerOnOffCommandHandler {
        DimmerPercentCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof PercentType;
        }

        @Override
        public int getCommandCode(InsteonChannelConfiguration conf, Command cmd) {
            return cmd != PercentType.ZERO ? 0x11 : 0x13;
        }

        @Override
        public int getLevel(InsteonChannelConfiguration conf, Command cmd) {
            int level = ((PercentType) cmd).intValue();
            return (int) Math.ceil(level * 255.0 / 100); // round up
        }

        @Override
        public int getGroup(InsteonChannelConfiguration conf) {
            return -1; // no support for broadcast request to specific level
        }

        @Override
        public void updateState(InsteonChannelConfiguration conf, Command cmd) {
            feature.publish((State) cmd, StateChangeType.ALWAYS);
        }
    }

    /**
     * Brigthen/dim command handler
     */
    @NonNullByDefault
    public static class BrigthenDimCommandHandler extends DimmerOnOffCommandHandler {
        BrigthenDimCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof IncreaseDecreaseType;
        }

        @Override
        public int getCommandCode(InsteonChannelConfiguration conf, Command cmd) {
            return cmd == IncreaseDecreaseType.INCREASE ? 0x15 : 0x16;
        }

        @Override
        public int getLevel(InsteonChannelConfiguration conf, Command cmd) {
            return 0x00; // not parsed
        }

        @Override
        public int getGroup(InsteonChannelConfiguration conf) {
            return conf.getBroadcastGroup();
        }

        @Override
        public void updateState(InsteonChannelConfiguration conf, Command cmd) {
            PercentType state = (PercentType) feature.getLastState();
            if (state != null) {
                // update last state by one step (32 steps from off to full brightness)
                int delta = (int) Math.round((cmd == IncreaseDecreaseType.INCREASE ? 1 : -1) * 100 / 32.0);
                int level = Math.max(0, Math.min(100, state.intValue() + delta));
                feature.publish(new PercentType(level), StateChangeType.ALWAYS);
            }
        }
    }

    /**
     * Rollershutter up/down command handler
     */
    @NonNullByDefault
    public static class RollershutterUpDownCommandHandler extends OnOffCommandHandler {
        RollershutterUpDownCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof UpDownType;
        }

        @Override
        public int getCommandCode(InsteonChannelConfiguration conf, Command cmd) {
            return 0x17; // manual change start
        }

        @Override
        public int getLevel(InsteonChannelConfiguration conf, Command cmd) {
            return cmd == UpDownType.UP ? 0x01 : 0x00; // up or down
        }

        @Override
        public int getGroup(InsteonChannelConfiguration conf) {
            return conf.getBroadcastGroup();
        }

        @Override
        public void updateRelatedStates(InsteonChannelConfiguration conf, Command cmd, boolean isBroadcast) {
            // no need to update related states on up/down commands
        }
    }

    /**
     * Rollershutter stop command handler
     */
    @NonNullByDefault
    public static class RollershutterStopCommandHandler extends OnOffCommandHandler {
        RollershutterStopCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd == StopMoveType.STOP;
        }

        @Override
        public int getCommandCode(InsteonChannelConfiguration conf, Command cmd) {
            return 0x18; // manual change stop
        }

        @Override
        public int getLevel(InsteonChannelConfiguration conf, Command cmd) {
            return 0x00; // not parsed
        }

        @Override
        public int getGroup(InsteonChannelConfiguration conf) {
            return conf.getBroadcastGroup();
        }
    }

    /**
     * Switch on/off command handler
     */
    @NonNullByDefault
    public static class SwitchOnOffCommandHandler extends OnOffCommandHandler {
        SwitchOnOffCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public int getCommandCode(InsteonChannelConfiguration conf, Command cmd) {
            return cmd == OnOffType.ON ? 0x11 : 0x13;
        }

        @Override
        public int getGroup(InsteonChannelConfiguration conf) {
            return conf.getBroadcastGroup();
        }
    }

    /**
     * Fast on/off command handler
     */
    @NonNullByDefault
    public static class FastOnOffCommandHandler extends OnOffCommandHandler {
        FastOnOffCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public int getCommandCode(InsteonChannelConfiguration conf, Command cmd) {
            return cmd == OnOffType.ON ? 0x12 : 0x14;
        }

        @Override
        public int getLevel(InsteonChannelConfiguration conf, Command cmd) {
            return 0x00; // not parsed
        }

        @Override
        public int getGroup(InsteonChannelConfiguration conf) {
            return conf.getBroadcastGroup();
        }
    }

    /**
     * Manual change command handler
     */
    @NonNullByDefault
    public static class ManualChangeCommandHandler extends OnOffCommandHandler {
        ManualChangeCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof StringType;
        }

        @Override
        public int getCommandCode(InsteonChannelConfiguration conf, Command cmd) {
            String action = ((StringType) cmd).toString();
            return action.equals("STOP") ? 0x18 : 0x17; // stop or start
        }

        @Override
        public int getLevel(InsteonChannelConfiguration conf, Command cmd) {
            String direction = ((StringType) cmd).toString();
            return direction.equals("BRIGHTEN") ? 0x01 : 0x00; // brighten or dim
        }

        @Override
        public int getGroup(InsteonChannelConfiguration conf) {
            return conf.getBroadcastGroup();
        }

        @Override
        public void updateRelatedStates(InsteonChannelConfiguration conf, Command cmd, boolean isBroadcast) {
            String action = ((StringType) cmd).toString();
            // only update related states on manual change stop action
            if (action.equals("STOP")) {
                super.updateRelatedStates(conf, cmd, isBroadcast);
            }
        }
    }

    /**
     * Broadcast on/off command handler
     */
    @NonNullByDefault
    public static class BroadcastOnOffCommandHandler extends SwitchOnOffCommandHandler {
        BroadcastOnOffCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleCommand(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev) {
            if (conf.getBroadcastGroup() != -1) {
                super.handleCommand(conf, cmd, dev);
            } else {
                logger.warn("{}: unable to determine broadcast group on channel {}", nm(), conf.getChannelName());
            }
        }
    }

    /**
     * Keypad bitmask command handler
     */
    @NonNullByDefault
    public static class KeypadBitmaskCommandHandler extends CustomBitmaskCommandHandler {
        KeypadBitmaskCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public int getBitNumber() {
            return getIntParameter("group", -1) - 1;
        }
    }

    /**
     * Keypad button command handler
     */
    @NonNullByDefault
    public static class KeypadButtonCommandHandler extends SwitchOnOffCommandHandler {
        KeypadButtonCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleCommand(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev) {
            if (cmd == OnOffType.OFF && isAlwaysOnToggle()) {
                // ignore off command when keypad button toggle mode is always on
                if (logger.isDebugEnabled()) {
                    logger.debug("{}: {} toggle mode is always on, ignoring off command", nm(), feature.getName());
                }
                // reset to last state if defined, defaulting to on state
                State state = feature.getLastState() == null ? OnOffType.ON : feature.getLastState();
                feature.publish(state, StateChangeType.ALWAYS);
            } else if (conf.getBooleanParameter("ledOnly", false)) {
                // set led button if "ledOnly" channel config parameter is set
                if (logger.isDebugEnabled()) {
                    logger.debug("{}: setting led button state only", nm());
                }
                setLEDButton(conf, cmd, dev);
            } else if (conf.getBroadcastGroup() == -1) {
                // set led button if broadcast group not defined, as fallback
                if (logger.isDebugEnabled()) {
                    logger.debug("{}: unable to determine broadcast group, setting led button state instead", nm());
                }
                setLEDButton(conf, cmd, dev);
            } else {
                // handle standard button command based on broadcast group
                super.handleCommand(conf, cmd, dev);
            }
        }

        protected String getButtonSuffix() {
            return StringUtils.capitalize(feature.getName()); // e.g. "buttonA" => "ButtonA"
        }

        private boolean isAlwaysOnToggle() {
            State toggleMode = feature.getDevice().getLastState(
                    KEYPAD_TOGGLE_MODE_FEATURE + getButtonSuffix());
            return toggleMode == OnOffType.OFF; // always on when toggle mode off
        }

        private void setLEDButton(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev) {
            KeypadLEDButtonCommandHandler handler = new KeypadLEDButtonCommandHandler(feature);
            handler.addParameters(parameters);
            handler.handleCommand(conf, cmd, dev);
        }

        @NonNullByDefault
        private class KeypadLEDButtonCommandHandler extends KeypadBitmaskCommandHandler {
            KeypadLEDButtonCommandHandler(DeviceFeature f) {
                super(f);
            }

            @Override
            public int getBitmask(Command cmd) {
                int bitmask = super.getBitmask(cmd);
                if (bitmask != -1) {
                    int onMask = feature.getDevice().getIntLastMsgValue(
                            KEYPAD_ON_MASK_FEATURE + getButtonSuffix(), 0);
                    int offMask = feature.getDevice().getIntLastMsgValue(
                            KEYPAD_OFF_MASK_FEATURE + getButtonSuffix(), 0);
                    if (logger.isTraceEnabled()) {
                        logger.trace("{}: bitmask:{} onMask:{} offMask:{}", nm(), ByteUtils.getBinaryString(bitmask),
                                ByteUtils.getBinaryString(onMask), ByteUtils.getBinaryString(offMask));
                    }
                    // apply keypad button on/off mask (radio group support)
                    bitmask &= ~offMask | onMask;
                }
                return bitmask;
            }
        }
    }

    /**
     * Keypad button config command handler
     */
    @NonNullByDefault
    public static class KeypadButtonConfigCommandHandler extends CustomCommandHandler {
        KeypadButtonConfigCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof StringType;
        }

        @Override
        public double getValue(Command cmd) {
            String config = ((StringType) cmd).toString();
            switch (config) {
                case "8-BUTTON":
                    return 0x06;
                case "6-BUTTON":
                    return 0x07;
                default:
                    logger.warn("{}: got unexpected button config command: {}, defaulting to: 6-BUTTON", nm(), config);
                    return 0x07;
            }
        }
    }

    /**
     * LED brightness command handler
     */
    @NonNullByDefault
    public static class LEDBrightnessCommandHandler extends CommandHandler {
        LEDBrightnessCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof OnOffType || cmd instanceof PercentType;
        }

        @Override
        public void handleCommand(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev) {
            try {
                int level = getLevel(cmd);
                int userData2 = getIntParameter("d2", -1);
                if (userData2 != -1) {
                    // set led brightness level
                    byte[] data = { (byte) 0x01, (byte) userData2, (byte) level };
                    Msg m = dev.makeExtendedMessage((byte) 0x1F, (byte) 0x2E, (byte) 0x00, data);
                    dev.enqueueRequest(m, feature);
                    if (logger.isDebugEnabled()) {
                        logger.debug("{}: sent led brightness level {} request to {}", nm(),
                                ByteUtils.getHexString(level), dev.getAddress());
                    }
                    // turn on/off led, using relevant feature if available
                    DeviceFeature f = dev.getFeature(LED_ON_OFF_FEATURE);
                    if (f != null) {
                        f.handleCommand(conf, level > 0 ? OnOffType.ON : OnOffType.OFF);
                    }
                } else {
                    logger.warn("{}: no d2 parameter specified in command handler", nm());
                }
            } catch (InvalidMessageTypeException e) {
                logger.warn("{}: invalid message: ", nm(), e);
            } catch (FieldException e) {
                logger.warn("{}: command send message creation error ", nm(), e);
            }
        }

        private int getLevel(Command cmd) {
            int level;
            if (cmd instanceof OnOffType) {
              level = cmd == OnOffType.OFF ? 0 : 100;
            } else if (cmd instanceof PercentType) {
              level = ((PercentType) cmd).intValue();
            } else {
              level = 50; // default 50% brightness
              logger.warn("{}: got unexpected command type, defaulting to {}%", nm(), level);
            }
            return (int) Math.round(level * getIntParameter("max", 0xFF) / 100.0);
        }
    }

    /**
     * Momentary on command handler
     */
    @NonNullByDefault
    public static class MomentaryOnCommandHandler extends CommandHandler {
        MomentaryOnCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd == OnOffType.ON;
        }

        @Override
        public void handleCommand(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev) {
            try {
                int cmd1 = getIntParameter("cmd1", -1);
                if (cmd1 != -1) {
                    Msg m = dev.makeStandardMessage((byte) 0x0F, (byte) cmd1, (byte) 0x00);
                    dev.enqueueRequest(m, feature);
                    if (logger.isDebugEnabled()) {
                        logger.debug("{}: sent {} request to {}", nm(), feature.getName(), dev.getAddress());
                    }
                } else {
                    logger.warn("{}: no cmd1 field specified", nm());
                }
            } catch (InvalidMessageTypeException e) {
                logger.warn("{}: invalid message: ", nm(), e);
            } catch (FieldException e) {
                logger.warn("{}: command send message creation error ", nm(), e);
            }
        }
    }

    /**
     * Operating flags command handler
     */
    @NonNullByDefault
    public static class OpFlagsCommandHandler extends CustomOnOffCommandHandler {
        OpFlagsCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleCommand(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev) {
            super.handleCommand(conf, cmd, dev);
            // update op flag last state if not retrievable (e.g. stayAwake)
            if (!isStateRetrievable()) {
                feature.publish((State) cmd, StateChangeType.ALWAYS);
            }
        }

        private boolean isStateRetrievable() {
            // op flag state is retrieved if a valid bit (0-7) parameter is defined
            int bit = getIntParameter("bit", -1);
            return bit >= 0 && bit <= 7;
        }

    }

    /**
     * Ramp abstract command handler
     */
    @NonNullByDefault
    public abstract static class RampCommandHandler extends CommandHandler {

        private static final double[] rampRateTimes = {
            0.1, 0.2, 0.3, 0.5, 2, 4.5, 6.5, 8.5, 19, 21.5, 23.5, 26, 28, 30, 32, 34,
            38.5, 43, 47, 60, 90, 120, 150, 180, 210, 240, 270, 300, 360, 420, 480
        };

        RampCommandHandler(DeviceFeature f) {
            super(f);
        }

        protected int getHalfRampRate(double rampTime) {
            return getRampRate(rampTime, true);
        }

        protected int getRampRate(double rampTime) {
            return getRampRate(rampTime, false);
        }

        private int getRampRate(double rampTime, boolean isHalfRate) {
            double[] rampTimes = getRampRateTimes(isHalfRate);
            int index = Arrays.binarySearch(rampTimes, rampTime);
            if (index < 0) {
                int insertionPoint = -index - 1;
                if (insertionPoint == 0) {
                    index = insertionPoint;
                } else if (insertionPoint == rampTimes.length) {
                    index = insertionPoint - 1;
                } else {
                    double lowDiff = Math.abs(rampTimes[insertionPoint - 1] - rampTime);
                    double highDiff = Math.abs(rampTimes[insertionPoint] - rampTime);
                    // update index to closest index based on smallest interval at insertion point
                    index = lowDiff >= highDiff ? insertionPoint : insertionPoint - 1;
                }
            }
            // return ramp rate based on descending index value,
            //  compensating for index 0 being relevant in half rate time array only
            return rampTimes.length - index - (isHalfRate ? 1 : 0);
        }

        private double[] getRampRateTimes(boolean isHalfRate) {
            if (isHalfRate) {
                int length = (int) Math.ceil(rampRateTimes.length / 2.0); // round up array length
                double[] halfRampRateTimes = new double[length];
                for (int i = 0; i < rampRateTimes.length; i+=2) {
                    halfRampRateTimes[i/2] = rampRateTimes[i];
                }
                return halfRampRateTimes;
            }
            return rampRateTimes;
        }
    }

    /**
     * Ramp dimmer command handler
     */
    @NonNullByDefault
    public static class RampDimmerCommandHandler extends RampCommandHandler {
        RampDimmerCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof OnOffType || cmd instanceof PercentType;
        }

        @Override
        public void handleCommand(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev) {
            try {
                double rampTime = getRampTime(conf);
                int level = getLevel(cmd);
                int cmd1 = getCommandCode(dev, level);
                int cmd2 = getEncodedValue(rampTime, level);
                Msg m = dev.makeStandardMessage((byte) 0x0F, (byte) cmd1, (byte) cmd2);
                dev.enqueueRequest(m, feature);
                if (logger.isDebugEnabled()) {
                    logger.debug("{}: sent level {} with ramp time {}s to {}", nm(), cmd, rampTime, dev.getAddress());
                }
            } catch (InvalidMessageTypeException e) {
                logger.warn("{}: invalid message: ", nm(), e);
            } catch (FieldException e) {
                logger.warn("{}: command send message creation error ", nm(), e);
            }
        }

        private int getCommandCode(InsteonDevice dev, int level) {
            // older device with firmware up to 0x41 uses commands 0x2E/0x2F, while newer uses 0x34/0x35
            if (dev.getProductData().getFirmwareVersion() <= 0x41) {
                return level > 0 ? 0x2E : 0x2F;
            } else {
                return level > 0 ? 0x34 : 0x35;
            }
        }

        private int getEncodedValue(double rampTime, int level) {
            int highByte = (int) Math.round(level * 0x0F / 100.0) & 0x0F;
            int lowByte = getHalfRampRate(rampTime) & 0x0F;
            return highByte << 4 | lowByte;
        }

        private int getLevel(Command cmd) {
            if (cmd instanceof OnOffType) {
                return cmd == OnOffType.ON ? 0xFF : 0x00;
            } else if (cmd instanceof PercentType) {
                return ((PercentType) cmd).intValue();
            } else {
                logger.warn("{}: got unexpected command type, defaulting to: 0", nm());
                return 0;
            }
        }

        private double getRampTime(InsteonChannelConfiguration conf) {
            double rampTime = conf.getDoubleParameter("ramptime", -1);
            if (rampTime >= 0.1 && rampTime <= 480) {
                return rampTime;
            } else {
                logger.warn("{}: ramptime parameter must be between 0.1 and 480 sec on channel {}", nm(),
                        conf.getChannelName());
            }
            return 2; // default medium setting (2 seconds)
        }
    }

    /**
     * Ramp rate command handler
     */
    @NonNullByDefault
    public static class RampRateCommandHandler extends RampCommandHandler {
        RampRateCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof DecimalType || cmd instanceof QuantityType;
        }

        @Override
        public void handleCommand(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev) {
            try {
                int level = getLevel(cmd);
                byte[] data = { (byte) getIntParameter("group", 1), (byte) 0x05, (byte) level };
                Msg m = dev.makeExtendedMessage((byte) 0x1F, (byte) 0x2E, (byte) 0x00, data);
                dev.enqueueRequest(m, feature);
                if (logger.isDebugEnabled()) {
                    logger.debug("{}: sent ramp time {} to {}", nm(), cmd, dev.getAddress());
                }
            } catch (InvalidMessageTypeException e) {
                logger.warn("{}: invalid message: ", nm(), e);
            } catch (FieldException e) {
                logger.warn("{}: command send message creation error ", nm(), e);
            }
        }

        private int getLevel(Command cmd) {
            double rampTime = getRampTime(cmd);
            return getRampRate(rampTime);
        }

        @SuppressWarnings("unchecked")
        private double getRampTime(Command cmd) {
            if (cmd instanceof DecimalType) {
              return ((DecimalType) cmd).doubleValue();
            } else if (cmd instanceof QuantityType) {
              return  ((QuantityType<Time>) cmd).toUnit(SmartHomeUnits.SECOND).doubleValue();
            } else {
              logger.warn("{}: got unexpected command type, defaulting to: 2s", nm());
              return 2; // default medium setting (2 seconds)
            }
        }
    }

    /**
     * FanLinc fan command handler
     */
    @NonNullByDefault
    public static class FanLincFanCommandHandler extends SecondaryOnOffCommandHandler {
        FanLincFanCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof StringType;
        }

        @Override
        public int getCommandCode(InsteonChannelConfiguration conf, Command cmd) {
            String mode = ((StringType) cmd).toString();
            return mode != "OFF" ? 0x11 : 0x13;
        }

        @Override
        public int getLevel(InsteonChannelConfiguration conf, Command cmd) {
            String mode = ((StringType) cmd).toString();
            switch (mode) {
                case "OFF":
                    return 0x00;
                case "LOW":
                    return 0x55;
                case "MEDIUM":
                    return 0xAA;
                case "HIGH":
                    return 0xFF;
                default:
                    logger.warn("{}: got unexpected fan mode command: {}, defaulting to: OFF", nm(), mode);
                    return 0x00;
            }
        }
    }

    /**
     * I/O linc momentary duration command handler
     */
    @NonNullByDefault
    public static class IOLincMomentaryDurationCommandHandler extends CommandHandler {
        IOLincMomentaryDurationCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof QuantityType;
        }

        @Override
        public void handleCommand(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev) {
            try {
                int prescaler = 1;
                int delay = (int) Math.round(getDuration(cmd) * 10);
                if (delay > 255) {
                    prescaler = (int) Math.ceil(delay / 255.0);
                    delay = (int) Math.round(delay / (double) prescaler);
                }
                // define ext command message to set momentary duration delay
                Msg mdelay = dev.makeExtendedMessage((byte) 0x1F, (byte) 0x2E, (byte) 0x00,
                        new byte[] { (byte) 0x01, (byte) 0x06, (byte) delay });
                // define ext command message to set momentary duration prescaler
                Msg mprescaler = dev.makeExtendedMessage((byte) 0x1F, (byte) 0x2E, (byte) 0x00,
                        new byte[] { (byte) 0x01, (byte) 0x07, (byte) prescaler });
                // enqueue requests
                dev.enqueueRequest(mdelay, feature);
                if (logger.isDebugEnabled()) {
                    logger.debug("{}: sent momentary duration delay {} request to {}", nm(),
                            ByteUtils.getHexString(delay), dev.getAddress());
                }
                dev.enqueueRequest(mprescaler, feature);
                if (logger.isDebugEnabled()) {
                    logger.debug("{}: sent momentary duration prescaler {} request to {}", nm(),
                            ByteUtils.getHexString(prescaler), dev.getAddress());
                }
            } catch (InvalidMessageTypeException e) {
                logger.warn("{}: invalid message: ", nm(), e);
            } catch (FieldException e) {
                logger.warn("{}: command send message creation error ", nm(), e);
            }
        }

        @SuppressWarnings("unchecked")
        private double getDuration(Command cmd) {
            QuantityType<Time> time = (QuantityType<Time>) cmd;
            return time.toUnit(SmartHomeUnits.SECOND).doubleValue();
        }
    }

    /**
     * I/O linc relay mode command handler
     */
    @NonNullByDefault
    public static class IOLincRelayModeCommandHandler extends CommandHandler {
        IOLincRelayModeCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof StringType;
        }

        @Override
        public void handleCommand(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev) {
            try {
                for (Map.Entry<Integer, String> opFlagCmd : getOpFlagCmds(cmd).entrySet()) {
                    Msg m = dev.makeExtendedMessage((byte) 0x1F, (byte) 0x20, opFlagCmd.getKey().byteValue());
                    dev.enqueueRequest(m, feature);
                    if (logger.isDebugEnabled()) {
                        logger.debug("{}: sent op flag {} request to {}", nm(), opFlagCmd.getValue(), dev.getAddress());
                    }
                }
            } catch (InvalidMessageTypeException e) {
                logger.warn("{}: invalid message: ", nm(), e);
            } catch (FieldException e) {
                logger.warn("{}: command send message creation error ", nm(), e);
            }
        }

        private Map<Integer, String> getOpFlagCmds(Command cmd) {
            Map<Integer, String> commands = new HashMap<>();
            String mode = ((StringType) cmd).toString();
            switch (mode) {
                case "LATCHING":
                    commands.put(0x07, "momentary mode OFF");
                    break;
                case "MOMENTARY_A":
                    commands.put(0x06, "momentary mode ON");
                    commands.put(0x13, "momentary trigger on/off OFF");
                    commands.put(0x15, "momentary sensor follow OFF");
                    break;
                case "MOMENTARY_B":
                    commands.put(0x06, "momentary mode ON");
                    commands.put(0x12, "momentary trigger on/off ON");
                    commands.put(0x15, "momentary sensor follow OFF");
                    break;
                case "MOMENTARY_C":
                    commands.put(0x06, "momentary mode ON");
                    commands.put(0x13, "momentary trigger on/off OFF");
                    commands.put(0x14, "momentary sensor follow ON");
                    break;
                default:
                    logger.warn("{}: got unexpected relay mode command: {}", nm(), mode);
            }
            return commands;
        }
    }

    /**
     * Thermostat fan mode command handler
     */
    @NonNullByDefault
    public static class ThermostatFanModeCommandHandler extends CustomCommandHandler {
        ThermostatFanModeCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof StringType;
        }

        @Override
        public double getValue(Command cmd) {
            String mode = ((StringType) cmd).toString();
            switch (mode) {
                case "AUTO":
                    return 0x08;
                case "ON":
                    return 0x07;
                default:
                    logger.warn("{}: got unexpected fan mode command: {}, defaulting to: AUTO", nm(), mode);
                    return 0x08;
            }
        }
    }

    /**
     * Thermostat system mode command handler
     */
    @NonNullByDefault
    public static class ThermostatSystemModeCommandHandler extends CustomCommandHandler {
        ThermostatSystemModeCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof StringType;
        }

        @Override
        public double getValue(Command cmd) {
            String mode = ((StringType) cmd).toString();
            switch (mode) {
                case "OFF":
                    return 0x09;
                case "HEAT":
                    return 0x04;
                case "COOL":
                    return 0x05;
                case "AUTO":
                    return 0x06;
                case "PROGRAM":
                    return 0x0A;
                default:
                    logger.warn("{}: got unexpected system mode command: {}, defaulting to: PROGRAM", nm(), mode);
                    return 0x0A;
            }
        }
    }

    /**
     * Venstar thermostat system mode handler
     */
    @NonNullByDefault
    public static class VenstarSystemModeCommandHandler extends CustomCommandHandler {
        VenstarSystemModeCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof StringType;
        }

        @Override
        public double getValue(Command cmd) {
            String mode = ((StringType) cmd).toString();
            switch (mode) {
                case "OFF":
                    return 0x09;
                case "HEAT":
                    return 0x04;
                case "COOL":
                    return 0x05;
                case "AUTO":
                    return 0x06;
                case "PROGRAM_HEAT":
                    return 0x0A;
                case "PROGRAM_COOL":
                    return 0x0B;
                case "PROGRAM_AUTO":
                    return 0x0C;
                default:
                    logger.warn("{}: got unexpected system mode command: {}, defaulting to: PROGRAM_AUTO", nm(), mode);
                    return 0x0C;
            }
        }
    }

    /**
     * Thermostat temperature format command handler
     */
    @NonNullByDefault
    public static class ThermostatTemperatureFormatCommandHandler extends CustomBitmaskCommandHandler {
        ThermostatTemperatureFormatCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof StringType;
        }

        @Override
        public boolean shouldSetBit(Command cmd) {
            String format = ((StringType) cmd).toString();
            switch (format) {
                case "FAHRENHEIT":
                    return false; // 0x00 (clear)
                case "CELSIUS":
                    return true;  // 0x01 (set)
                default:
                    logger.warn("{}: got unexpected temperature format command: {}, defaulting to: FAHRENHEIT", nm(),
                            format);
                    return false;
            }
        }
    }

    /**
     * Venstar thermostat temperature format command handler
     */
    @NonNullByDefault
    public static class VenstarTemperatureFormatCommandHandler extends CustomCommandHandler {
        VenstarTemperatureFormatCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof StringType;
        }

        @Override
        public double getValue(Command cmd) {
            String format = ((StringType) cmd).toString();
            switch (format) {
                case "FAHRENHEIT":
                    return 0x00;
                case "CELSIUS":
                    return 0x01;
                default:
                    logger.warn("{}: got unexpected temperature format command: {}, defaulting to: FAHRENHEIT", nm(),
                            format);
                    return 0x00;
            }
        }
    }

    /**
     * Thermostat time format command handler
     */
    @NonNullByDefault
    public static class ThermostatTimeFormatCommandHandler extends CustomBitmaskCommandHandler {
        ThermostatTimeFormatCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof StringType;
        }

        @Override
        public boolean shouldSetBit(Command cmd) {
            String format = ((StringType) cmd).toString();
            switch (format) {
                case "12H":
                    return false; // 0x00 (clear)
                case "24H":
                    return true;  // 0x01 (set)
                default:
                    logger.warn("{}: got unexpected temperature format command: {}, defaulting to: 12H", nm(),
                            format);
                    return false;
            }
        }
    }

    /**
     * Thermostat sync time command handler
     */
    @NonNullByDefault
    public static class ThermostatSyncTimeCommandHandler extends MomentaryOnCommandHandler {
        ThermostatSyncTimeCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleCommand(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev) {
            try {
                ZonedDateTime time = ZonedDateTime.now();
                byte[] data = { (byte) 0x02, (byte) (time.getDayOfWeek().getValue() % 7), (byte) time.getHour(),
                          (byte) time.getMinute(), (byte) time.getSecond() };
                Msg m = dev.makeExtendedMessageCRC2((byte) 0x1F, (byte) 0x2E, (byte) 0x02, data);
                dev.enqueueRequest(m, feature);
                if (logger.isDebugEnabled()) {
                    logger.debug("{}: sent set time data request to {}", nm(), dev.getAddress());
                }
            } catch (InvalidMessageTypeException e) {
                logger.warn("{}: invalid message: ", nm(), e);
            } catch (FieldException e) {
                logger.warn("{}: command send message creation error ", nm(), e);
            }
        }
    }

    /**
     * X10 generic abstract command handler
     */
    @NonNullByDefault
    public abstract static class X10CommandHandler extends CommandHandler {
        X10CommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public void handleCommand(InsteonChannelConfiguration conf, Command cmd, InsteonDevice dev) {
            try {
                int unitCode = getUnitCode(dev);
                int cmdCode = getCommandCode(cmd, dev);
                Msg munit = dev.makeX10Message((byte) unitCode, (byte) 0x00); // send unit code
                dev.enqueueRequest(munit, feature);
                Msg mcmd = dev.makeX10Message((byte) cmdCode, (byte) 0x80); // send command code
                dev.enqueueRequest(mcmd, feature);
                if (logger.isDebugEnabled()) {
                    logger.debug("{}: sent {} request to {}", nm(), cmd, dev.getAddress());
                }
            } catch (InvalidMessageTypeException e) {
                logger.warn("{}: invalid message: ", nm(), e);
            } catch (FieldException e) {
                logger.warn("{}: command send message creation error ", nm(), e);
            }
        }

        private int getUnitCode(InsteonDevice dev) {
            int houseCode = dev.getX10HouseCode();
            int unitCode = dev.getX10UnitCode();
            return houseCode << 4 | unitCode;
        }

        public abstract int getCommandCode(Command cmd, InsteonDevice dev);
    }

    /**
     * X10 on/off command handler
     */
    @NonNullByDefault
    public static class X10OnOffCommandHandler extends X10CommandHandler {
        X10OnOffCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof OnOffType;
        }

        @Override
        public int getCommandCode(Command cmd, InsteonDevice dev) {
            int houseCode = dev.getX10HouseCode();
            int cmdCode = cmd == OnOffType.ON ? X10.Command.ON.code() : X10.Command.OFF.code();
            return houseCode << 4 | cmdCode;
        }
    }

    /**
     * X10 percent command handler
     */
    @NonNullByDefault
    public static class X10PercentCommandHandler extends X10CommandHandler {

        private static final int[] x10LevelCodes = { 0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15 };

        X10PercentCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof PercentType;
        }

        @Override
        public int getCommandCode(Command cmd, InsteonDevice dev) {
            //
            // I did not have hardware that would respond to the PRESET_DIM codes.
            // This code path needs testing.
            //
            int level = ((PercentType) cmd).intValue() * 32 / 100;
            int levelCode = x10LevelCodes[level % 16];
            int cmdCode = level >= 16 ? X10.Command.PRESET_DIM_2.code() : X10.Command.PRESET_DIM_1.code();
            return levelCode << 4 | cmdCode;
        }
    }

    /**
     * X10 increase/decrease command handler
     */
    @NonNullByDefault
    public static class X10IncreaseDecreaseCommandHandler extends X10CommandHandler {
        X10IncreaseDecreaseCommandHandler(DeviceFeature f) {
            super(f);
        }

        @Override
        public boolean canHandle(Command cmd) {
            return cmd instanceof IncreaseDecreaseType;
        }

        @Override
        public int getCommandCode(Command cmd, InsteonDevice dev) {
            int houseCode = dev.getX10HouseCode();
            int cmdCode = cmd == IncreaseDecreaseType.INCREASE ? X10.Command.BRIGHT.code() : X10.Command.DIM.code();
            return houseCode << 4 | cmdCode;
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
    public static @Nullable <T extends CommandHandler> T makeHandler(String name,
            Map<String, @Nullable String> params, DeviceFeature f) {
        String cname = CommandHandler.class.getName() + "$" + name;
        try {
            Class<?> c = Class.forName(cname);
            @SuppressWarnings("unchecked")
            Class<? extends T> dc = (Class<? extends T>) c;
            T ch = dc.getDeclaredConstructor(DeviceFeature.class).newInstance(f);
            ch.addParameters(params);
            return ch;
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | IllegalArgumentException
                | InvocationTargetException | NoSuchMethodException | SecurityException e) {
            logger.warn("error trying to create message handler: {}", name, e);
        }
        return null;
    }
}
