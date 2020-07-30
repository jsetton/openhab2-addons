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
package org.openhab.binding.insteon.internal.handler;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.openhab.binding.insteon.internal.config.X10DeviceConfiguration;
import org.openhab.binding.insteon.internal.device.InsteonAddress;
import org.openhab.binding.insteon.internal.device.InsteonDevice;
import org.openhab.binding.insteon.internal.device.DeviceType;
import org.openhab.binding.insteon.internal.device.DeviceTypeLoader;
import org.openhab.binding.insteon.internal.device.ProductData;

/**
 * The {@link InsteonSceneHandler} is responsible for handling scene commands, which are
 * sent to one of the channels.
 *
 * @author Jeremy Setton - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings("null")
public class X10DeviceHandler extends InsteonDeviceHandler {

    private @Nullable X10DeviceConfiguration config;

    public X10DeviceHandler(Thing thing) {
        super(thing, null);
    }

    @Override
    public void initialize() {
        config = getConfigAs(X10DeviceConfiguration.class);

        scheduler.execute(() -> {
            if (getBridge() == null) {
                String msg = "An Insteon network bridge has not been selected for this scene.";
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                return;
            }

            String houseCode = config.getHouseCode();
            if (!houseCode.matches("[A-P]")) {
                String msg = "Unable to start X10 device, the house code '" + houseCode
                        + "' is invalid. It must be between A and P.";
                logger.warn("{}", msg);

                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                return;
            }

            int unitCode = config.getUnitCode();
            if (unitCode < 1 && unitCode > 16) {
                String msg = "Unable to start X10 device, the unit code '" + unitCode
                        + "' is invalid. It must be between 1 and 16.";
                logger.warn("{}", msg);

                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                return;
            }

            String deviceTypeName = config.getDeviceType();
            if (!deviceTypeName.startsWith("X10")) {
                String msg = "Unable to start X10 device, unsupported device type '" + deviceTypeName + "'.";
                logger.warn("{}", msg);

                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                return;
            }

            DeviceType deviceType = DeviceTypeLoader.instance().getDeviceType(deviceTypeName);
            if (deviceType == null) {
                String msg = "Unable to start X10 device, invalid device type '" + deviceTypeName + "'.";
                logger.warn("{}", msg);

                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                return;
            }

            InsteonAddress x10Address = new InsteonAddress(houseCode + "." + unitCode);
            if (getInsteonBinding().getDevice(x10Address) != null) {
                String msg = "A X10 device already exists with the house code '" + houseCode
                        + " and unit code '" + unitCode + ".";
                logger.warn("{}", msg);

                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                return;
            }

            ProductData productData = ProductData.makeX10Product(deviceType);
            InsteonDevice device = getInsteonBinding().makeNewDevice(this, x10Address, productData);
            if (device == null) {
                String msg = "Unable to initialize X10 device with the house code '" + houseCode
                        + " and unit code '" + unitCode + ".";
                logger.warn("{}", msg);

                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                return;
            }

            getInsteonBinding().addDevice(x10Address, device);
            setDevice(device);
            initializeChannels();
            updateThingStatus();
        });
    }

    @Override
    public void dispose() {
        String houseCode = config.getHouseCode();
        int unitCode = config.getUnitCode();
        String address = houseCode + "." + unitCode;
        if (getBridge() != null && InsteonAddress.isValid(address)) {
            getInsteonBinding().removeDevice(new InsteonAddress(address));

            if (logger.isDebugEnabled()) {
                logger.debug("removed {} house code = {} unit code = {}", getThing().getUID().getAsString(),
                        houseCode, unitCode);
            }
        }

        super.dispose();
    }

    @Override
    public String getThingInfo() {
        String thingId = getThing().getUID().getAsString();
        String houseCode = config.getHouseCode();
        int unitCode = config.getUnitCode();
        String deviceType = config.getDeviceType();
        String channelIds = String.join(", ", getChannelIds());

        StringBuilder builder = new StringBuilder(thingId);
        builder.append(" houseCode = ");
        builder.append(houseCode);
        builder.append(" unitCode = ");
        builder.append(unitCode);
        builder.append(" deviceType = ");
        builder.append(deviceType);
        builder.append(" channels = ");
        builder.append(channelIds);

        return builder.toString();
    }
}
