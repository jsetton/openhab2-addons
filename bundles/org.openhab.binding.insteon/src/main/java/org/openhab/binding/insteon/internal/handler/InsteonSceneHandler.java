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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.openhab.binding.insteon.internal.config.InsteonSceneConfiguration;

/**
 * The {@link InsteonSceneHandler} is responsible for handling scene commands, which are
 * sent to one of the channels.
 *
 * @author Jeremy Setton - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings("null")
public class InsteonSceneHandler extends InsteonBaseHandler {

    private @Nullable InsteonSceneConfiguration config;

    public InsteonSceneHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        config = getConfigAs(InsteonSceneConfiguration.class);

        scheduler.execute(() -> {
            if (getBridge() == null) {
                String msg = "An Insteon network bridge has not been selected for this scene.";
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                return;
            }

            int group = config.getGroup();
            if (group <= 0 && group >= 255) {
                String msg = "Unable to start Insteon scene, the group number '" + group
                        + "' is invalid. It must be between 1 and 254.";
                logger.warn("{}", msg);

                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                return;
            }

            if (getInsteonBinding().getSceneHandler(group) != null) {
                String msg = "a scene already exists with the group number '" + group + "'.";
                logger.warn("{}", msg);

                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
                return;
            }

            List<String> channels = new ArrayList<>();
            for (Channel channel : thing.getChannels()) {
                channel.getConfiguration().put("group", group);
                channels.add(channel.getUID().getId());
            }

            getInsteonBinding().addSceneHandler(group, this);
            setDevice(getInsteonBinding().getModemDevice());
            updateThingStatus();

            if (logger.isDebugEnabled()) {
                logger.debug("{}", getThingInfo());
            }
        });
    }

    @Override
    public void dispose() {
        int group = config.getGroup();
        getInsteonBinding().removeSceneHandler(group);

        if (logger.isDebugEnabled()) {
            logger.debug("removed {} group = {}", getThing().getUID().getAsString(), group);
        }

        super.dispose();
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        if (!getInsteonBinding().isModemDBComplete()) {
            if (logger.isDebugEnabled()) {
                logger.debug("channel {} linking skipped because modem database not available yet.",
                        channelUID.getAsString());
            }
            return;
        }

        super.channelLinked(channelUID);
    }

    @Override
    public String getThingInfo() {
        String thingId = getThing().getUID().getAsString();
        int group = config.getGroup();
        String channelIds = String.join(", ", getChannelIds());

        StringBuilder builder = new StringBuilder(thingId);
        builder.append(" group = ");
        builder.append(group);
        builder.append(" channels = ");
        builder.append(channelIds);

        return builder.toString();
    }

    @Override
    public void updateThingStatus() {
        // check if scene group number valid
        int group = config.getGroup();
        if (getInsteonBinding().isModemDBComplete() && !getInsteonBinding().isValidBroadcastGroup(group)) {
            String msg = "Unable to find scene group number " + group + " in modem database.";
            logger.warn("{}", msg);

            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
            return;
        }

        super.updateThingStatus();
    }

    public void update() {
        setDevice(getInsteonBinding().getModemDevice());
        relinkChannels();
        updateThingStatus();
    }
}
