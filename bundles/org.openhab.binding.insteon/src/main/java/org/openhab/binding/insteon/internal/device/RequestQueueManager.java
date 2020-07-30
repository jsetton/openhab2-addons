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

import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class that manages all the per-device request queues using a single thread.
 *
 * - Each device has its own request queue, and the RequestQueueManager keeps a
 * queue of queues.
 * - Each entry in requestQueues corresponds to a single device's request queue.
 * A device should never be more than once in requestQueues.
 * - A hash map (requestQueueHash) is kept in sync with requestQueues for
 * faster lookup in case a request queue is modified and needs to be
 * rescheduled.
 *
 * @author Bernd Pfrommer - Initial contribution
 * @author Rob Nielsen - Port to openHAB 2 insteon binding
 */
@NonNullByDefault
@SuppressWarnings("null")
public class RequestQueueManager {
    private static @Nullable RequestQueueManager instance = null;
    private final Logger logger = LoggerFactory.getLogger(RequestQueueManager.class);
    private @Nullable Thread queueThread = null;
    private PriorityQueue<RequestQueue> requestQueues = new PriorityQueue<>();
    private HashMap<InsteonDevice, @Nullable RequestQueue> requestQueueHash = new HashMap<>();
    private volatile boolean keepRunning = true;
    private AtomicBoolean paused = new AtomicBoolean(false);

    private RequestQueueManager() {
        queueThread = new Thread(new RequestQueueReader());
        queueThread.setName("Insteon Request Queue Reader");
        queueThread.setDaemon(true);
        queueThread.start();
    }

    /**
     * Add device to global request queue.
     *
     * @param dev the device to add
     * @param delay time (in milliseconds) to delay queue processing
     * @param urgent if urgent device request
     */
    public void addQueue(InsteonDevice dev, long delay, boolean urgent) {
        synchronized (requestQueues) {
            long time = System.currentTimeMillis() + delay;
            RequestQueue q = requestQueueHash.get(dev);
            if (q == null) {
                if (logger.isTraceEnabled()) {
                    logger.trace("scheduling request for device {} in {} msec, urgent: {}", dev.getAddress(),
                            delay, urgent);
                }
                q = new RequestQueue(dev, time, urgent);
            } else {
                if (logger.isTraceEnabled()) {
                    logger.trace("queue for dev {} is already scheduled in {} msec", dev.getAddress(),
                            q.getExpirationTime() - System.currentTimeMillis());
                }
                if (!requestQueues.remove(q)) {
                    logger.warn("queue for {} should be there, report as bug!", dev);
                }
                requestQueueHash.remove(dev);
            }
            long expTime = q.getExpirationTime();
            if (expTime > time) {
                q.setExpirationTime(time);
            }
            // add the queue back in after (maybe) having modified
            // the expiration time
            requestQueues.add(q);
            requestQueueHash.put(dev, q);
            requestQueues.notify();
        }
    }

    /**
     * Pause request queue thread
     */
    public void pause() {
        if (logger.isDebugEnabled()) {
            logger.debug("pausing request queue thread");
        }
        paused.set(true);
        synchronized (requestQueues) {
            requestQueues.notify();
        }
    }

    /**
     * Resume request queue thread
     */
    public void resume() {
        if (logger.isDebugEnabled()) {
            logger.debug("resuming request queue thread");
        }
        paused.set(false);
        synchronized (paused) {
            paused.notify();
        }
    }

    /**
     * Stops request queue thread
     */
    private void stopThread() {
        logger.debug("stopping request queue thread");
        if (queueThread != null) {
            keepRunning = false;
            synchronized (paused) {
                paused.notifyAll();
            }
            synchronized (requestQueues) {
                requestQueues.notifyAll();
            }
            try {
                logger.debug("waiting for thread to join");
                queueThread.join();
                logger.debug("request queue thread exited!");
            } catch (InterruptedException e) {
                logger.warn("got interrupted waiting for thread exit ", e);
            }
            queueThread = null;
        }
    }

    @NonNullByDefault
    class RequestQueueReader implements Runnable {
        @Override
        public void run() {
            logger.debug("starting request queue thread");
            while (keepRunning) {
                try {
                    synchronized (paused) {
                        if (paused.get()) {
                            if (logger.isTraceEnabled()) {
                                logger.trace("waiting for request queue thread to resume");
                            }
                            paused.wait();
                            continue;
                        }
                    }
                    synchronized (requestQueues) {
                        if (requestQueues.isEmpty()) {
                            if (logger.isTraceEnabled()) {
                                logger.trace("waiting for request queues to fill");
                            }
                            requestQueues.wait();
                            continue;
                        }
                        RequestQueue q = requestQueues.peek();
                        long now = System.currentTimeMillis();
                        long expTime = q.getExpirationTime();
                        InsteonDevice dev = q.getDevice();
                        if (expTime > now) {
                            //
                            // The head of the queue is not up for processing yet, wait().
                            //
                            if (logger.isTraceEnabled()) {
                                logger.trace("request queue head: {} must wait for {} msec", dev.getAddress(),
                                        expTime - now);
                            }
                            requestQueues.wait(expTime - now);
                            //
                            // note that the wait() can also return because of changes to
                            // the queue, not just because the time expired!
                            //
                            continue;
                        }
                        //
                        // The head of the queue has expired and can be processed!
                        //
                        q = requestQueues.poll(); // remove front element
                        requestQueueHash.remove(dev); // and remove from hash map
                        boolean urgent = q.isUrgent();
                        long nextExp = dev.processRequestQueue(now);
                        if (nextExp > 0) {
                            q = new RequestQueue(dev, nextExp, urgent);
                            requestQueues.add(q);
                            requestQueueHash.put(dev, q);
                            if (logger.isTraceEnabled()) {
                                logger.trace("device queue for {} rescheduled in {} msec", dev.getAddress(),
                                        nextExp - now);
                            }
                        } else {
                            // remove from hash since queue is no longer scheduled
                            if (logger.isDebugEnabled()) {
                                logger.debug("device queue for {} is empty!", dev.getAddress());
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    logger.warn("request queue thread got interrupted, breaking.", e);
                    break;
                }
            }
            logger.debug("exiting request queue thread!");
        }
    }

    @NonNullByDefault
    public static class RequestQueue implements Comparable<RequestQueue> {
        private InsteonDevice device;
        private long expirationTime;
        private boolean urgent;

        RequestQueue(InsteonDevice dev, long expirationTime, boolean urgent) {
            this.device = dev;
            this.expirationTime = expirationTime;
            this.urgent = urgent;
        }

        public InsteonDevice getDevice() {
            return device;
        }

        public long getExpirationTime() {
            return expirationTime;
        }

        public void setExpirationTime(long t) {
            expirationTime = t;
        }

        public boolean isUrgent() {
            return urgent;
        }

        @Override
        public int compareTo(RequestQueue a) {
            if (!urgent && a.urgent) {
                return 1;
            } else if (urgent && !a.urgent) {
                return -1;
            } else {
                return (int) (expirationTime - a.expirationTime);
            }
        }
    }

    @NonNullByDefault
    public static synchronized @Nullable RequestQueueManager instance() {
        if (instance == null) {
            instance = new RequestQueueManager();
        }
        return (instance);
    }

    public static synchronized void destroyInstance() {
        if (instance != null) {
            instance.stopThread();
            instance = null;
        }
    }
}
