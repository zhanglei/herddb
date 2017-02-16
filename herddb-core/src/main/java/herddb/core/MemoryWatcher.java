/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package herddb.core;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watchs the actual use of memory and plan actions in order to reduce memory usage.
 *
 * @author enrico.olivelli
 */
public class MemoryWatcher {

    private float overallMaximumLimit;
    private float lowerbound;
    private static final MemoryMXBean jvmMemory = ManagementFactory.getMemoryMXBean();

//    public MemoryWatcher(ServerConfiguration config) {
//        this(
//            config.getLong(ServerConfiguration.PROPERTY_MEMORY_LIMIT_REFERENCE,
//                ServerConfiguration.PROPERTY_MEMORY_LIMIT_REFERENCE_DEFAULT),
//            config.getLong(ServerConfiguration.PROPERTY_MAX_LIVE_MEMORY_THRESHOLD,
//                ServerConfiguration.PROPERTY_MAX_LIVE_MEMORY_THRESHOLD_DEFAULT),
//            config.getLong(ServerConfiguration.PROPERTY_MAX_LIVE_MEMORY_LOWERBOUND,
//                ServerConfiguration.PROPERTY_MAX_LIVE_MEMORY_LOWERBOUND_DEFAULT)
//        );
//    }

    public MemoryWatcher(long maxMemoryReferenceValue,
        float overallMaximumLimitPercent,
        float lowerboundPercend) {
//        LOTHRUIN
//        if (maxMemoryReferenceValue == 0) {
//            maxMemoryReferenceValue = jvmMemory.getHeapMemoryUsage().getMax();
//            LOG.log(Level.INFO, "This database will use " + (maxMemoryReferenceValue / (1024 * 1024)) + " MBytes as reference max memory value (computed from JVM JMX MemoryMXBean)");
//        } else {
            LOG.log(Level.INFO, "This database will use " + (maxMemoryReferenceValue / (1024 * 1024)) + " MBytes as reference max memory value");
//        }

        this.overallMaximumLimit = (maxMemoryReferenceValue * overallMaximumLimitPercent) / 100f;
        LOG.log(Level.INFO, "Overall maximum limit is " + (long) (overallMaximumLimit / (1024 * 1024)) + " MBytes: the database will try not to use more than this amount of memory for live data");

        this.lowerbound = (overallMaximumLimit * lowerboundPercend) / 100f;
        LOG.log(Level.INFO, "Lowerbound is " + (long) ((lowerbound / (1024 * 1024))) + " MBytes: the database will try to reach to this value while reducing memory usage");
    }
    private static final Logger LOG = Logger.getLogger(MemoryWatcher.class.getName());

    public void run(DBManager dbManager) {
        long explicitUsage = dbManager.handleLocalMemoryUsage();
        if (explicitUsage < overallMaximumLimit) {
            LOG.log(Level.FINE, "Memory OK {0} used ({1} limit)",
                new Object[]{explicitUsage, overallMaximumLimit + ""});
            return;
        }
        long reclaim = (long) (explicitUsage - lowerbound);
        LOG.log(Level.SEVERE, "Low-Memory {0} used ({1} limit). To reclaim: {3}",
            new Object[]{explicitUsage, overallMaximumLimit + "", reclaim + ""});
        dbManager.tryReleaseMemory(reclaim, new CheckLowerBound(dbManager));
        explicitUsage = dbManager.handleLocalMemoryUsage();
        LOG.log(Level.FINE, "After reclaim: memory {0} used ({1} limit)",
            new Object[]{explicitUsage, overallMaximumLimit});

    }

    private class CheckLowerBound implements Supplier<Boolean> {

        private DBManager dbManager;

        public CheckLowerBound(DBManager dbManager) {
            this.dbManager = dbManager;
        }

        @Override
        public Boolean get() {
            long explicitUsage = dbManager.handleLocalMemoryUsage();
            if (explicitUsage < overallMaximumLimit) {
                LOG.log(Level.FINE, "Memory {0} used ({1} limit)",
                    new Object[]{explicitUsage, overallMaximumLimit + ""});
                return true;
            }
            long _reclaim = (long) (explicitUsage - lowerbound);
            LOG.log(Level.FINE, "Memory {0} used ({1} limit. To reclaim {3}",
                new Object[]{explicitUsage, overallMaximumLimit + "", (_reclaim / (1024 * 1024)) + " MB"});
            return _reclaim <= 0;
        }
    }

    public float getOverallMaximumLimit() {
        return overallMaximumLimit;
    }

    public void setOverallMaximumLimit(float overallMaximumLimit) {
        this.overallMaximumLimit = overallMaximumLimit;
    }

    public float getLowerbound() {
        return lowerbound;
    }

    public void setLowerbound(float lowerbound) {
        this.lowerbound = lowerbound;
    }

}
