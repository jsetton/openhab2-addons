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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * The DeviceType class holds device type definitions that are read from
 * an xml file.
 *
 * @author Bernd Pfrommer - Initial contribution
 * @author Rob Nielsen - Port to openHAB 2 insteon binding
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
@SuppressWarnings("null")
public class DeviceType {
    private String name;
    private Map<String, Boolean> flags = new HashMap<>();
    private HashMap<String, FeatureEntry> features = new HashMap<>();
    private HashMap<String, FeatureGroup> featureGroups = new HashMap<>();

    /**
     * Constructor
     *
     * @param name  the name for this device type
     * @param flags the flags for this device type
     */
    public DeviceType(String name, Map<String, Boolean> flags) {
        this.name = name;
        this.flags = flags;
    }

    /**
     * Get name
     *
     * @return the name for this device type
     */
    public String getName() {
        return name;
    }

    /**
     * Get flags
     *
     * @return all flags for this device type
     */
    public Map<String, Boolean> getFlags() {
        return flags;
    }

    /**
     * Get supported features
     *
     * @return all features that this device type supports
     */
    public List<FeatureEntry> getFeatures() {
        return new ArrayList<>(features.values());
    }

    /**
     * Get all feature groups
     *
     * @return all feature groups of this device type
     */
    public List<FeatureGroup> getFeatureGroups() {
        return new ArrayList<>(featureGroups.values());
    }

    /**
     * Adds feature to this device type
     *
     * @param name name of the feature, which acts as key for lookup later
     * @param fe feature entry to add
     * @return true if add succeeded, false if feature was already there
     */
    public boolean addFeature(String name, FeatureEntry fe) {
        if (features.containsKey(name)) {
            return false;
        }
        features.put(name, fe);
        return true;
    }

    /**
     * Adds feature group to device type
     *
     * @param name name of the feature group, which acts as key for lookup later
     * @param fg feature group to add
     * @return true if add succeeded, false if feature group was already there
     */
    public boolean addFeatureGroup(String name, FeatureGroup fg) {
        if (featureGroups.containsKey(name)) {
            return false;
        }
        featureGroups.put(name, fg);
        return true;
    }

    @Override
    public String toString() {
        String s = "name:" + name + "|features";
        for (FeatureEntry fe : features.values()) {
            s += ":" + fe.getName() + "=" + fe.getType();
        }
        s += "|groups";
        for (FeatureGroup fg : featureGroups.values()) {
            s += ":" + fg.getName() + "=" + String.join(",", fg.getConnectedFeatures());
        }
        return s;
    }

    /**
     * Class that reflects a feature entry
     */
    @NonNullByDefault
    public static class FeatureEntry {
        private String name;
        private String type;
        private Map<String, @Nullable String> parameters;

        public FeatureEntry(String name, String type, Map<String, @Nullable String> parameters) {
            this.name = name;
            this.type = type;
            this.parameters = parameters;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public Map<String, @Nullable String> getParameters() {
            return parameters;
        }
    }

    /**
     * Class that reflects a feature group
     */
    @NonNullByDefault
    public static class FeatureGroup extends FeatureEntry {
        private List<String> connectFeatures = new ArrayList<>();

        public FeatureGroup(String name, String type, Map<String, @Nullable String> parameters) {
            super(name, type, parameters);
        }

        public List<String> getConnectedFeatures() {
            return connectFeatures;
        }

        public void addConnectedFeature(String f) {
            connectFeatures.add(f);
        }
    }

}
