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
package org.openhab.binding.insteon.internal.utils;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Byte utility functions
 *
 * @author Jeremy Setton - Initial contribution
 */
@NonNullByDefault
public class BitwiseUtils {
    public static int getBitFlag(int bitmask, int bit) {
        return (bitmask >> bit) & 0x1;
    }

    public static boolean isBitFlagSet(int bitmask, int bit) {
        return getBitFlag(bitmask, bit) == 0x1;
    }

    public static int setBitFlag(int bitmask, int bit) {
        return bitmask | (0x1 << bit);
    }

    public static int clearBitFlag(int bitmask, int bit) {
        return bitmask & ~(0x1 << bit);
    }
}
