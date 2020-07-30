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
package org.openhab.binding.insteon.internal.message;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.insteon.internal.device.InsteonAddress;
import org.openhab.binding.insteon.internal.utils.ByteUtils;
import org.openhab.binding.insteon.internal.utils.ParsingException;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contains an Insteon Message consisting of the raw data, and the message definition.
 * For more info, see the public Insteon Developer's Guide, 2nd edition,
 * and the Insteon Modem Developer's Guide.
 *
 * @author Bernd Pfrommer - Initial contribution
 * @author Daniel Pfrommer - openHAB 1 insteonplm binding
 * @author Rob Nielsen - Port to openHAB 2 insteon binding
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
@SuppressWarnings("null")
public class Msg {
    private static final Logger logger = LoggerFactory.getLogger(Msg.class);

    /**
     * Represents the direction of the message from the host's view.
     * The host is the machine to which the modem is attached.
     */
    public enum Direction {
        TO_MODEM("TO_MODEM"),
        FROM_MODEM("FROM_MODEM");

        private static HashMap<String, Direction> map = new HashMap<>();

        private String directionString;

        static {
            map.put(TO_MODEM.getDirectionString(), TO_MODEM);
            map.put(FROM_MODEM.getDirectionString(), FROM_MODEM);
        }

        Direction(String dirString) {
            this.directionString = dirString;
        }

        public String getDirectionString() {
            return directionString;
        }

        public static Direction getDirectionFromString(String dir) {
            return map.get(dir);
        }
    }

    // has the structure of all known messages
    private static final Map<String, @Nullable Msg> MSG_MAP = new HashMap<>();
    // maps between command number and the length of the header
    private static final Map<Integer, @Nullable Integer> HEADER_MAP = new HashMap<>();
    // has templates for all message from modem to host
    private static final Map<Integer, @Nullable Msg> REPLY_MAP = new HashMap<>();

    private int headerLength = -1;
    private byte @Nullable [] data = null;
    private MsgDefinition definition = new MsgDefinition();
    private Direction direction = Direction.TO_MODEM;
    private long quietTime = 0;
    private boolean replayed = false;
    private long timestamp = System.currentTimeMillis();

    /**
     * Constructor
     *
     * @param headerLength length of message header (in bytes)
     * @param data byte array with message
     * @param dataLength length of byte array data (in bytes)
     * @param dir direction of the message (from/to modem)
     */
    public Msg(int headerLength, byte[] data, int dataLength, Direction dir) {
        this.headerLength = headerLength;
        this.direction = dir;
        initialize(data, 0, dataLength);
    }

    /**
     * Copy constructor, needed to make a copy of the templates when
     * generating messages from them.
     *
     * @param m the message to make a copy of
     */
    public Msg(Msg m) {
        headerLength = m.headerLength;
        data = m.data.clone();
        // the message definition usually doesn't change, but just to be sure...
        definition = new MsgDefinition(m.definition);
        direction = m.direction;
    }

    static {
        // Use xml msg loader to load configs
        try {
            InputStream stream = FrameworkUtil.getBundle(Msg.class).getResource("/msg_definitions.xml").openStream();
            if (stream != null) {
                HashMap<String, Msg> msgs = XMLMessageReader.readMessageDefinitions(stream);
                MSG_MAP.putAll(msgs);
            } else {
                logger.warn("could not get message definition resource!");
            }
        } catch (IOException e) {
            logger.warn("i/o error parsing xml insteon message definitions", e);
        } catch (ParsingException e) {
            logger.warn("parse error parsing xml insteon message definitions", e);
        } catch (FieldException e) {
            logger.warn("got field exception while parsing xml insteon message definitions", e);
        }
        buildHeaderMap();
        buildLengthMap();
    }

    //
    // ------------------ simple getters and setters -----------------
    //

    /**
     * Experience has shown that if Insteon messages are sent in close succession,
     * only the first one will make it. The quiet time parameter says how long to
     * wait after a message before the next one can be sent.
     *
     * @return the time (in milliseconds) to pause after message has been sent
     */
    public long getQuietTime() {
        return quietTime;
    }

    public byte @Nullable [] getData() {
        return data;
    }

    public int getLength() {
        return data.length;
    }

    public int getHeaderLength() {
        return headerLength;
    }

    public Direction getDirection() {
        return direction;
    }

    public MsgDefinition getDefinition() {
        return definition;
    }

    public byte getCommandNumber() {
        return ((data == null || data.length < 2) ? -1 : data[1]);
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isPureNack() {
        return (data.length == 2 && data[1] == 0x15);
    }

    public boolean isExtended() {
        try {
            byte flags = getByte("messageFlags");
            return ((flags & 0x10) == 0x10);
        } catch (FieldException e) {
            // do nothing, we'll return false
        }
        return false;
    }

    public boolean isFromAddress(InsteonAddress addr) {
        try {
            return getAddress("fromAddress").equals(addr);
        } catch (FieldException e) {
            // do nothing, we'll return false
        }
        return false;
    }

    public boolean isEcho() {
        // if is pure NACK or has the ACK/NACK field, it is an echo message to our request,
        // otherwise it is out-of-band, i.e. unsolicited
        return isPureNack() || containsField("ACK/NACK");
    }

    public boolean isOfType(MsgType t) {
        return t == getType();
    }

    public boolean isBroadcast() {
        return isOfType(MsgType.ALL_LINK_BROADCAST) || isOfType(MsgType.BROADCAST);
    }

    public boolean isAllLinkBroadcast() {
        return isOfType(MsgType.ALL_LINK_BROADCAST);
    }

    public boolean isCleanup() {
        return isOfType(MsgType.ALL_LINK_CLEANUP);
    }

    public boolean isAllLink() {
        return isOfType(MsgType.ALL_LINK_BROADCAST) || isOfType(MsgType.ALL_LINK_CLEANUP);
    }

    public boolean isDirect() {
        return isOfType(MsgType.DIRECT);
    }

    public boolean isAckOfDirect() {
        return isOfType(MsgType.ACK_OF_DIRECT);
    }

    public boolean isAckOrNackOfDirect() {
        return isOfType(MsgType.ACK_OF_DIRECT) || isOfType(MsgType.NACK_OF_DIRECT);
    }

    public boolean isAllLinkCleanupAckOrNack() {
        return isOfType(MsgType.ALL_LINK_CLEANUP_ACK) || isOfType(MsgType.ALL_LINK_CLEANUP_NACK);
    }

    public boolean isX10() {
        try {
            int cmd = getInt("Cmd");
            if (cmd == 0x63 || cmd == 0x52) {
                return true;
            }
        } catch (FieldException e) {
        }
        return false;
    }

    public boolean isReplayed() {
        return replayed;
    }

    public void setDefinition(MsgDefinition d) {
        definition = d;
    }

    public void setQuietTime(long t) {
        quietTime = t;
    }

    public void setIsReplayed(boolean b) {
        replayed = b;
    }

    public void addField(Field f) {
        definition.addField(f);
    }

    public boolean containsField(String key) {
        return definition.containsField(key);
    }

    public int getHopsLeft() throws FieldException {
        int hops = (getByte("messageFlags") & 0x0c) >> 2;
        return hops;
    }

    /**
     * Will initialize the message with a byte[], an offset, and a length
     *
     * @param newData the src byte array
     * @param offset the offset in the src byte array
     * @param len the length to copy from the src byte array
     */
    private void initialize(byte[] newData, int offset, int len) {
        data = new byte[len];
        if (offset >= 0 && offset < newData.length) {
            System.arraycopy(newData, offset, data, 0, len);
        } else {
            logger.warn("intialize(): Offset out of bounds!");
        }
    }

    /**
     * Will put a byte at the specified key
     *
     * @param key the string key in the message definition
     * @param value the byte to put
     */
    public void setByte(@Nullable String key, byte value) throws FieldException {
        Field f = definition.getField(key);
        f.setByte(data, value);
    }

    /**
     * Will put an int at the specified field key
     *
     * @param key the name of the field
     * @param value the int to put
     */
    public void setInt(String key, int value) throws FieldException {
        Field f = definition.getField(key);
        f.setInt(data, value);
    }

    /**
     * Will put address bytes at the field
     *
     * @param key the name of the field
     * @param addr the address to put
     */
    public void setAddress(String key, InsteonAddress addr) throws FieldException {
        Field f = definition.getField(key);
        f.setAddress(data, addr);
    }

    /**
     * Will fetch a byte
     *
     * @param key the name of the field
     * @return the byte
     */
    public byte getByte(String key) throws FieldException {
        return (definition.getField(key).getByte(data));
    }

    /**
     * Will fetch a byte array starting at a certain field
     *
     * @param key the name of the first field
     * @param numBytes number of bytes to get
     * @return the byte array
     */
    public byte[] getBytes(String key, int numBytes) throws FieldException {
        int offset = definition.getField(key).getOffset();
        if (offset < 0 || offset + numBytes > data.length) {
            throw new FieldException("data index out of bounds!");
        }
        byte[] section = new byte[numBytes];
        System.arraycopy(data, offset, section, 0, numBytes);
        return section;
    }

    /**
     * Will fetch address from a field
     *
     * @param key the name of the field
     * @return the address
     */
    public InsteonAddress getAddress(String key) throws FieldException {
        return (definition.getField(key).getAddress(data));
    }

    /**
     * Returns a byte array starting at a certain field as an up to 32-bit integer
     *
     * @param key the name of the first field
     * @param numBytes number of bytes to use for conversion
     * @return the integer
     */
    public int getBytesAsInt(String key, int numBytes) throws FieldException {
        if (numBytes < 1 || numBytes > 4) {
            throw new FieldException("number of bytes out of bounds!");
        }
        int i = 0;
        int shift = 8 * (numBytes - 1);
        for (byte b : getBytes(key, numBytes)) {
            i |= (b & 0xFF) << shift;
            shift -= 8;
        }
        return i;
    }

    /**
     * Returns a byte as a 8-bit integer
     *
     * @param key the name of the field
     * @return the integer
     */
    public int getInt(String key) throws FieldException {
        return getByte(key) & 0xFF;
    }

    /**
     * Returns a 2-byte array starting at a certain field as a 16-bit integer
     *
     * @param key the name of the first field
     * @return the integer
     */
    public int getInt16(String key) throws FieldException {
        return getBytesAsInt(key, 2);
    }

    /**
     * Returns a 3-byte array starting at a certain field as a 24-bit integer
     *
     * @param key the name of the first field
     * @return the integer
     */
    public int getInt24(String key) throws FieldException {
        return getBytesAsInt(key, 3);
    }

    /**
     * Returns a 4-byte array starting at a certain field as a 32-bit integer
     *
     * @param key the name of the first field
     * @return the integer
     */
    public int getInt32(String key) throws FieldException {
        return getBytesAsInt(key, 4);
    }

    /**
     * Returns a byte as a hex string
     *
     * @param  key the name of the field
     * @return the hex string
     */
    public String getHexString(String key) throws FieldException {
        return ByteUtils.getHexString(getByte(key));
    }

    /**
     * Returns a byte array starting at a certain field as a hex string
     *
     * @param key the name of the field
     * @param numBytes number of bytes to get
     * @return the hex string
     */
    public String getHexString(String key, int numBytes) throws FieldException {
        return ByteUtils.getHexString(getBytes(key, numBytes), numBytes);
    }

    /**
     * Returns the address
     *
     * @param key the name of the field
     * @return the address if available, otherwise null
     */
    public @Nullable InsteonAddress getAddressOrNull(String key) {
        try {
            return getAddress(key);
        } catch (FieldException e) {
            // do nothing, we'll return null
        }
        return null;
    }

    /**
     * Returns group based on specific message characteristics
     *
     * @return group number if available, otherwise -1
     */
    public int getGroup() {
        try {
            if (isAllLinkBroadcast()) {
                return getAddress("toAddress").getLowByte() & 0xFF;
            }
            if (isCleanup()) {
                return getInt("command2");
            }
            if (isExtended()) {
                byte cmd1 = getByte("command1");
                byte cmd2 = getByte("command2");
                // group number for specific extended msg located in userData1 byte
                if (cmd1 == 0x2E && cmd2 == 0x00) {
                    return getInt("userData1");
                }
            }
        } catch (FieldException e) {
            logger.warn("unable to determine group on msg: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Returns msg type based on message flags
     *
     * @return msg type
     */
    public MsgType getType() {
        try {
            return MsgType.fromValue(getByte("messageFlags"));
        } catch (FieldException | IllegalArgumentException e) {
            return MsgType.INVALID;
        }
    }

    /**
     * Sets the userData fields from a byte array
     *
     * @param data
     */
    public void setUserData(byte[] arg) {
        byte[] data = Arrays.copyOf(arg, 14); // appends zeros if short
        try {
            setByte("userData1", data[0]);
            setByte("userData2", data[1]);
            setByte("userData3", data[2]);
            setByte("userData4", data[3]);
            setByte("userData5", data[4]);
            setByte("userData6", data[5]);
            setByte("userData7", data[6]);
            setByte("userData8", data[7]);
            setByte("userData9", data[8]);
            setByte("userData10", data[9]);
            setByte("userData11", data[10]);
            setByte("userData12", data[11]);
            setByte("userData13", data[12]);
            setByte("userData14", data[13]);
        } catch (FieldException e) {
            logger.warn("got field exception on msg {}:", e.getMessage());
        }
    }

    /**
     * Calculate the CRC using the older 1-byte method
     *
     * @return the calculated crc
     * @throws FieldException
     */
    public int calculateCRC() throws FieldException {
        int crc = 0;
        byte[] bytes = getBytes("command1", 15); // skip userData14
        for (byte b : bytes) {
            crc += b;
        }
        return (~crc + 1) & 0xFF;
    }

    /**
     * Calculate the CRC using the newer 2-byte method
     *
     * @return the calculated crc
     * @throws FieldException
     */
    public int calculateCRC2() throws FieldException {
        int crc = 0;
        byte[] bytes = getBytes("command1", 14); // skip userData13/14
        for (int loop = 0; loop < bytes.length; loop++) {
            int b = bytes[loop] & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                int fb = b & 0x01;
                if ((crc & 0x8000) == 0) {
                    fb = fb ^ 0x01;
                }
                if ((crc & 0x4000) == 0) {
                    fb = fb ^ 0x01;
                }
                if ((crc & 0x1000) == 0) {
                    fb = fb ^ 0x01;
                }
                if ((crc & 0x0008) == 0) {
                    fb = fb ^ 0x01;
                }
                crc = (crc << 1) | fb;
                b = b >> 1;
            }
        }
        return crc & 0xFFFF;
    }

    /**
     * Check if message has a valid CRC using the older 1-byte method
     *
     * @return true if valid
     */
    public boolean hasValidCRC() {
        try {
            return getInt("userData14") == calculateCRC();
        } catch (FieldException e) {
            logger.warn("got field exception on msg {}:", this, e);
        }
        return false;
    }

    /**
     * Check if message has a valid CRC using the newer 2-byte method is valid
     *
     * @return true if valid
     */
    public boolean hasValidCRC2() {
        try {
            return getInt16("userData13") == calculateCRC2();
        } catch (FieldException e) {
            logger.warn("got field exception on msg {}:", this, e);
        }
        return false;
    }

    /**
     * Set the calculated CRC using the older 1-byte method
     */
    public void setCRC() {
        try {
            int crc = calculateCRC();
            setByte("userData14", (byte) crc);
        } catch (FieldException e) {
            logger.warn("got field exception on msg {}:", this, e);
        }
    }

    /**
     * Set the calculated CRC using the newer 2-byte method
     */
    public void setCRC2() {
        try {
            int crc = calculateCRC2();
            setByte("userData13", (byte) ((crc >> 8) & 0xFF));
            setByte("userData14", (byte) (crc & 0xFF));
        } catch (FieldException e) {
            logger.warn("got field exception on msg {}:", this, e);
        }
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof Msg)) {
            return false;
        }
        Msg m = (Msg) o;
        return Arrays.equals(data, m.getData());
    }

    @Override
    public String toString() {
        if (data == null) {
            return "undefined";
        }
        String s = (direction == Direction.TO_MODEM) ? "OUT:" : "IN:";
        // need to first sort the fields by offset
        Comparator<@Nullable Field> cmp = new Comparator<@Nullable Field>() {
            @Override
            public int compare(@Nullable Field f1, @Nullable Field f2) {
                return f1.getOffset() - f2.getOffset();
            }
        };
        TreeSet<@Nullable Field> fields = new TreeSet<>(cmp);
        for (@Nullable Field f : definition.getFields().values()) {
            fields.add(f);
        }
        for (Field f : fields) {
            if (f.getName().equals("messageFlags")) {
                byte b;
                try {
                    b = f.getByte(data);
                    MsgType t = MsgType.fromValue(b);
                    s += f.toString(data) + "=" + t.toString() + ":" + (b & 0x03) + ":" + ((b & 0x0c) >> 2) + "|";
                } catch (FieldException e) {
                    logger.warn("toString error: ", e);
                } catch (IllegalArgumentException e) {
                    logger.warn("toString msg type error: ", e);
                }
            } else {
                s += f.toString(data) + "|";
            }
        }
        return s;
    }

    /**
     * Factory method to create Msg from raw byte stream received from the
     * serial port.
     *
     * @param buf the raw received bytes
     * @param msgLen length of received buffer
     * @param isExtended whether it is an extended message or not
     * @return message, or null if the Msg cannot be created
     */
    public static @Nullable Msg createMessage(byte[] buf, int msgLen, boolean isExtended) {
        if (buf == null || buf.length < 2) {
            return null;
        }
        Msg template = REPLY_MAP.get(cmdToKey(buf[1], isExtended));
        if (template == null) {
            return null; // cannot find lookup map
        }
        if (msgLen != template.getLength()) {
            logger.warn("expected msg {} len {}, got {}", template.getCommandNumber(), template.getLength(), msgLen);
            return null;
        }
        Msg msg = new Msg(template.getHeaderLength(), buf, msgLen, Direction.FROM_MODEM);
        msg.setDefinition(template.getDefinition());
        return (msg);
    }

    /**
     * Finds the header length from the insteon command in the received message
     *
     * @param cmd the insteon command received in the message
     * @return the length of the header to expect
     */
    public static int getHeaderLength(byte cmd) {
        Integer len = HEADER_MAP.get(cmd & 0xFF);
        if (len == null) {
            return (-1); // not found
        }
        return len;
    }

    /**
     * Tries to determine the length of a received Insteon message.
     *
     * @param b Insteon message command received
     * @param isExtended flag indicating if it is an extended message
     * @return message length, or -1 if length cannot be determined
     */
    public static int getMessageLength(byte b, boolean isExtended) {
        int key = cmdToKey(b, isExtended);
        Msg msg = REPLY_MAP.get(key);
        if (msg == null) {
            return -1;
        }
        return msg.getLength();
    }

    /**
     * From bytes received thus far, tries to determine if an Insteon
     * message is extended or standard.
     *
     * @param buf the received bytes
     * @param len the number of bytes received so far
     * @param headerLength the known length of the header
     * @return true if it is definitely extended, false if cannot be
     *         determined or if it is a standard message
     */
    public static boolean isExtended(byte[] buf, int len, int headerLength) {
        if (headerLength <= 2) {
            return false;
        } // extended messages are longer
        if (len < headerLength) {
            return false;
        } // not enough data to tell if extended
        byte flags = buf[headerLength - 1]; // last byte says flags
        boolean isExtended = (flags & 0x10) == 0x10; // bit 4 is the message
        return (isExtended);
    }

    /**
     * Creates Insteon message (for sending) of a given type
     *
     * @param type the type of message to create, as defined in the xml file
     * @return reference to message created
     * @throws IOException if there is no such message type known
     */
    public static Msg makeMessage(String type) throws InvalidMessageTypeException {
        Msg m = MSG_MAP.get(type);
        if (m == null) {
            throw new InvalidMessageTypeException("unknown message type: " + type);
        }
        return new Msg(m);
    }

    private static int cmdToKey(byte cmd, boolean isExtended) {
        return (cmd + (isExtended ? 256 : 0));
    }

    private static void buildHeaderMap() {
        for (Msg m : MSG_MAP.values()) {
            if (m.getDirection() == Direction.FROM_MODEM) {
                HEADER_MAP.put(m.getCommandNumber() & 0xFF, m.getHeaderLength());
            }
        }
    }

    private static void buildLengthMap() {
        for (Msg m : MSG_MAP.values()) {
            if (m.getDirection() == Direction.FROM_MODEM) {
                int key = cmdToKey(m.getCommandNumber(), m.isExtended());
                REPLY_MAP.put(key, m);
            }
        }
    }
}
