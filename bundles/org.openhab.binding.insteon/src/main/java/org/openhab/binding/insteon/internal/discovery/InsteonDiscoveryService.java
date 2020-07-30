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
package org.openhab.binding.insteon.internal.discovery;

import static org.openhab.binding.insteon.internal.InsteonBindingConstants.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.openhab.binding.insteon.internal.device.InsteonAddress;
import org.openhab.binding.insteon.internal.device.ProductData;
import org.openhab.binding.insteon.internal.handler.InsteonNetworkHandler;
import org.openhab.binding.insteon.internal.InsteonBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link InsteonDiscoveryService} is responsible for device discovery.
 *
 * @author Rob Nielsen - Initial contribution
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
public class InsteonDiscoveryService extends AbstractDiscoveryService {
    private static final int SCAN_TIMEOUT = 2; // in seconds

    private final Logger logger = LoggerFactory.getLogger(InsteonDiscoveryService.class);

    private InsteonNetworkHandler handler;

    public InsteonDiscoveryService(InsteonNetworkHandler handler) {
        super(DISCOVERABLE_THING_TYPES_UIDS, SCAN_TIMEOUT, false);
        this.handler = handler;

        logger.debug("Initializing InsteonDiscoveryService");

        handler.setInsteonDiscoveryService(this);
    }

    @Override
    protected void startScan() {
        discoverMissingThings();
    }

    public void discoverMissingThings() {
        InsteonBinding insteonBinding = handler.getInsteonBinding();
        if (insteonBinding == null) {
            logger.debug("Insteon binding not initialized yet, scanning aborted.");
            return;
        }
        if (!insteonBinding.isModemDBComplete()) {
            logger.debug("Modem database not complete, scanning aborted.");
            return;
        }

        if (handler.isDeviceDiscoveryEnabled()) {
            addInsteonDevices(insteonBinding.getMissingDevices());
        } else {
            logger.debug("device discovery is disabled, no missing device will be discovered.");
        }

        if (handler.isSceneDiscoveryEnabled()) {
            addInsteonScenes(insteonBinding.getMissingScenes());
        } else {
            logger.debug("scene discovery is disabled, no missing scene will be discovered.");
        }
    }

    private void addInsteonDevices(Map<String, @Nullable ProductData> devices) {
        for (String address : devices.keySet()) {
            if (!InsteonAddress.isValid(address)) {
                logger.warn("Address {} must be formatted as XX.XX.XX", address);
                continue;
            }

            String name = address.replace(".", "");
            ThingUID uid = new ThingUID(DEVICE_THING_TYPE, name);
            Map<String, Object> properties = new HashMap<>();
            properties.put(DEVICE_ADDRESS_PROPERTY, address);
            String label = "Insteon Device " + address;
            ThingUID bridgeUID = handler.getThing().getUID();

            ProductData productData = devices.get(address);
            if (productData != null) {
                String deviceCategory = productData.getDeviceCategory();
                if (deviceCategory != null) {
                    properties.put(DEVICE_CATEGORY_PROPERTY, deviceCategory);
                }
                String subCategory = productData.getSubCategory();
                if (subCategory != null) {
                    properties.put(DEVICE_SUB_CATEGORY_PROPERTY, subCategory);
                }
                String description = productData.getFullDescription();
                if (description != null) {
                    label = "Insteon " + description;
                }
            }

            thingDiscovered(
                    DiscoveryResultBuilder.create(uid).withProperties(properties).withLabel(label)
                            .withBridge(bridgeUID).withRepresentationProperty(DEVICE_ADDRESS_PROPERTY).build());

            if (logger.isDebugEnabled()) {
                logger.debug("added Insteon device for address {}", address);
            }
        }
    }

    private void addInsteonScenes(List<Integer> scenes) {
        for (int group : scenes) {
            if (group <= 0 && group >= 255) {
                logger.warn("Group {} must be between 1 and 254", group);
                continue;
            }

            String name = "scene" + group;
            ThingUID uid = new ThingUID(SCENE_THING_TYPE, name);
            Map<String, Object> properties = new HashMap<>();
            properties.put(SCENE_GROUP_PROPERTY, group);
            String label = "Insteon Scene " + group;
            ThingUID bridgeUID = handler.getThing().getUID();

            thingDiscovered(
                    DiscoveryResultBuilder.create(uid).withProperties(properties).withLabel(label)
                            .withBridge(bridgeUID).withRepresentationProperty(SCENE_GROUP_PROPERTY).build());

            if (logger.isDebugEnabled()) {
                logger.debug("added Insteon scene for group {}", group);
            }
        }
    }
}
