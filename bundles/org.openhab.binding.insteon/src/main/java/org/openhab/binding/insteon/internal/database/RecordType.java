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

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.insteon.internal.message.FieldException;
import org.openhab.binding.insteon.internal.message.Msg;

/**
 * Represents Insteon all-links record type flags
 *
 * @author Jeremy Setton - Initial contribution
 */
@NonNullByDefault
public enum RecordType {
    CONTROLLER(0xC2, "CTRL"),
    RESPONDER(0x82, "RESP"),
    INACTIVE(0x02, "IDLE"),
    LAST(0x00, "LAST"),
    INVALID(0xFF, "UNKN");

    private static Map<Integer, @Nullable RecordType> map = new HashMap<>();
    static {
        for (RecordType type : RecordType.values()) {
            map.put(type.bitmask, type);
        }
    }

    private int bitmask;
    private String acronym;

    private RecordType(int bitmask, String acronym) {
        this.bitmask = bitmask;
        this.acronym = acronym;
    }

    public String getAcronym() {
        return acronym;
    }

    /**
     * Factory method for getting a RecordType from an Insteon record message
     *
     * @param  msg            the Insteon record message to parse
     * @return                the record type
     * @throws FieldException
     */
    public static RecordType fromRecordMsg(Msg msg) throws FieldException {
        if (msg.containsField("RecordFlags")) {
            return fromRecordFlags(msg.getByte("RecordFlags"));
        }
        if (msg.containsField("LinkCode")) {
            return fromLinkCode(msg.getByte("LinkCode"));
        }
        return RecordType.INVALID;
    }

    /**
     * Factory method for getting a RecordType from Insteon record flags
     *
     * @param  flags the Insteon record flags to use
     * @return       the record type
     */
    public static RecordType fromRecordFlags(byte flags) {
        // record flags bit 1 (high-water mark), 6 (control) & 7 (in use)
        int bitmask = flags & 0xC2;
        RecordType type = map.get(bitmask);
        return type == null ? RecordType.INVALID : type;
    }

    /**
     * Factory method for getting a RecordType from an Insteon record link code
     *
     * @param  linkCode the Insteon link code to use
     * @return          the record type
     */
    private static RecordType fromLinkCode(byte linkCode) {
        // link code 0x00 (responder); 0x01 (controller); 0xFF (deleted => invalid)
        int bitmask = linkCode << 6 & 0xFF ^ 0x82;
        RecordType type = map.get(bitmask);
        return type == null ? RecordType.INVALID : type;
    }
}
