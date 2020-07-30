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
package org.openhab.binding.insteon.internal;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.thing.ThingTypeUID;

/**
 * The {@link InsteonBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Rob Nielsen - Initial contribution
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
public class InsteonBindingConstants {
    public static final String BINDING_ID = "insteon";

    // List of all thing type uids
    public static final ThingTypeUID DEVICE_THING_TYPE = new ThingTypeUID(BINDING_ID, "device");
    public static final ThingTypeUID NETWORK_THING_TYPE = new ThingTypeUID(BINDING_ID, "network");
    public static final ThingTypeUID SCENE_THING_TYPE = new ThingTypeUID(BINDING_ID, "scene");
    public static final ThingTypeUID X10_THING_TYPE = new ThingTypeUID(BINDING_ID, "x10");

    // Set of discoverable thing type uids
    public static final Set<ThingTypeUID> DISCOVERABLE_THING_TYPES_UIDS = Collections
          .unmodifiableSet(Stream.of(DEVICE_THING_TYPE, SCENE_THING_TYPE)
          .collect(Collectors.toSet()));

    // Set of supported thing type uids
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Collections
          .unmodifiableSet(Stream.of(DEVICE_THING_TYPE, NETWORK_THING_TYPE, SCENE_THING_TYPE, X10_THING_TYPE)
          .collect(Collectors.toSet()));

    // Specific thing type config property names
    public static final String DEVICE_ADDRESS_PROPERTY = "address";
    public static final String DEVICE_CATEGORY_PROPERTY = "devCat";
    public static final String DEVICE_SUB_CATEGORY_PROPERTY = "subCat";
    public static final String SCENE_GROUP_PROPERTY = "group";

    // Specific feature names
    public static final String INSTEON_ENGINE_FEATURE = "insteonEngine";
    public static final String KEYPAD_OFF_MASK_FEATURE = "offMask";
    public static final String KEYPAD_ON_MASK_FEATURE = "onMask";
    public static final String KEYPAD_TOGGLE_MODE_FEATURE = "toggleMode";
    public static final String LED_ON_OFF_FEATURE = "ledOnOff";
    public static final String ON_LEVEL_FEATURE = "onLevel";
    public static final String STAY_AWAKE_FEATURE = "stayAwake";
    public static final String TEMPERATURE_FORMAT_FEATURE = "temperatureFormat";
    public static final String THERMOSTAT_SYSTEM_MODE_FEATURE = "systemMode";

    // Specific device types
    public static final String THERMOSTAT_VENSTAR_DEVICE_TYPE = "ClimateControl_VenstarThermostat";

    // Mapping of custom state description options
    public static final Map<String, String[]> CUSTOM_STATE_DESCRIPTION_OPTIONS;
    static {
        Map<String, String[]> options = new HashMap<>();
        // Thermostat Venstar System Mode
        options.put(THERMOSTAT_SYSTEM_MODE_FEATURE + ":" + THERMOSTAT_VENSTAR_DEVICE_TYPE, new String[] {
                "OFF", "HEAT", "COOL", "AUTO", "PROGRAM_HEAT", "PROGRAM_COOL", "PROGRAM_AUTO" });
        CUSTOM_STATE_DESCRIPTION_OPTIONS = Collections.unmodifiableMap(options);
    }
}
