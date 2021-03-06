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

package herddb.server.hammer;

import static herddb.core.TestUtils.commitTransaction;
import static herddb.core.TestUtils.execute;
import static herddb.core.TestUtils.scan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import herddb.core.DBManager;
import herddb.core.TestUtils;
import herddb.core.stats.TableManagerStats;
import herddb.model.DMLStatementExecutionResult;
import herddb.model.DataScanner;
import herddb.model.TableSpace;
import herddb.model.TransactionContext;
import herddb.server.Server;
import herddb.server.ServerConfiguration;
import herddb.utils.DataAccessor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

/**
 * Concurrent updates
 *
 * @author enrico.olivelli
 */
public abstract class DirectMultipleConcurrentUpdatesSuite {

    private static final int TABLESIZE = 2000;
    private static final int MULTIPLIER = 2;
    private static final int THREADPOLSIZE = 100;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();


    protected void performTest(boolean useTransactions, long checkPointPeriod, boolean withIndexes, boolean uniqueIndexes) throws Exception {
        Path baseDir = folder.newFolder().toPath();
        ServerConfiguration serverConfiguration = new ServerConfiguration(baseDir);

        serverConfiguration.set(ServerConfiguration.PROPERTY_MAX_LOGICAL_PAGE_SIZE, 10 * 1024);
        serverConfiguration.set(ServerConfiguration.PROPERTY_MAX_DATA_MEMORY, 1024 * 1024 / 4);
        serverConfiguration.set(ServerConfiguration.PROPERTY_MAX_PK_MEMORY, 1024 * 1024);
        serverConfiguration.set(ServerConfiguration.PROPERTY_CHECKPOINT_PERIOD, checkPointPeriod);
        serverConfiguration.set(ServerConfiguration.PROPERTY_DATADIR, folder.newFolder().getAbsolutePath());
        serverConfiguration.set(ServerConfiguration.PROPERTY_LOGDIR, folder.newFolder().getAbsolutePath());

        ConcurrentHashMap<String, Long> expectedValue = new ConcurrentHashMap<>();
        try (Server server = new Server(serverConfiguration)) {
            server.start();
            server.waitForStandaloneBoot();
            DBManager manager = server.getManager();
            execute(manager, "CREATE TABLE mytable (id string primary key, n1 long, n2 integer)", Collections.emptyList());

            if (withIndexes) {
                if (uniqueIndexes) {
                    // use n1 + id in order to not have collisions and lock timeouts
                    execute(manager, "CREATE UNIQUE INDEX theindex ON mytable (n1, id)", Collections.emptyList());
                } else {
                    execute(manager, "CREATE INDEX theindex ON mytable (n1)", Collections.emptyList());
                }
            }

            long tx = TestUtils.beginTransaction(manager, TableSpace.DEFAULT);
            for (int i = 0; i < TABLESIZE; i++) {
                TestUtils.executeUpdate(manager,
                        "INSERT INTO mytable (id,n1,n2) values(?,?,?)",
                        Arrays.asList("test_" + i, 1, 2), new TransactionContext(tx));
                expectedValue.put("test_" + i, 1L);
            }
            TestUtils.commitTransaction(manager, TableSpace.DEFAULT, tx);
            ExecutorService threadPool = Executors.newFixedThreadPool(THREADPOLSIZE);
            try {
                List<Future> futures = new ArrayList<>();
                AtomicLong updates = new AtomicLong();
                AtomicLong skipped = new AtomicLong();
                AtomicLong gets = new AtomicLong();
                for (int i = 0; i < TABLESIZE * MULTIPLIER; i++) {
                    futures.add(threadPool.submit(new Runnable() {
                                                      @Override
                                                      public void run() {
                                                          try {
                                                              boolean update = ThreadLocalRandom.current().nextBoolean();
                                                              int k = ThreadLocalRandom.current().nextInt(TABLESIZE);
                                                              long value = ThreadLocalRandom.current().nextInt(TABLESIZE);
                                                              long transactionId;
                                                              String key = "test_" + k;
                                                              Long actual = expectedValue.remove(key);
                                                              if (actual == null) {
                                                                  // another thread working on this entry, skip
                                                                  skipped.incrementAndGet();
                                                                  return;
                                                              }
                                                              if (update) {
                                                                  updates.incrementAndGet();
                                                                  DMLStatementExecutionResult updateResult =
                                                                          TestUtils.executeUpdate(manager,
                                                                                  "UPDATE mytable set n1=? WHERE id=?",
                                                                                  Arrays.asList(value, "test_" + k),
                                                                                  new TransactionContext(useTransactions ? TransactionContext.AUTOTRANSACTION_ID : TransactionContext.NOTRANSACTION_ID));
                                                                  long count = updateResult.getUpdateCount();
                                                                  transactionId = updateResult.transactionId;
                                                                  if (count <= 0) {
                                                                      throw new RuntimeException("not updated ?");
                                                                  }
                                                              } else {
                                                                  gets.incrementAndGet();
                                                                  DataScanner res = TestUtils.scan(manager,
                                                                          "SELECT * FROM mytable where id=?", Arrays.asList("test_" + k),
                                                                          new TransactionContext(useTransactions ? TransactionContext.AUTOTRANSACTION_ID : TransactionContext.NOTRANSACTION_ID));
                                                                  if (!res.hasNext()) {
                                                                      throw new RuntimeException("not found?");
                                                                  }
                                                                  res.close();
                                                                  transactionId = res.getTransactionId();
                                                                  // value did not change actually
                                                                  value = actual;
                                                              }
                                                              if (useTransactions) {
                                                                  if (transactionId <= 0) {
                                                                      throw new RuntimeException("no transaction ?");
                                                                  }
                                                                  commitTransaction(manager, TableSpace.DEFAULT, transactionId);
                                                              }
                                                              expectedValue.put(key, value);

                                                          } catch (Exception err) {
                                                              throw new RuntimeException(err);
                                                          }
                                                      }
                                                  }
                    ));
                }
                for (Future f : futures) {
                    f.get();
                }

                System.out.println("stats::updates:" + updates);
                System.out.println("stats::get:" + gets);
                assertTrue(updates.get() > 0);
                assertTrue(gets.get() > 0);

                List<String> erroredKeys = new ArrayList<>();
                for (Map.Entry<String, Long> entry : expectedValue.entrySet()) {
                    List<DataAccessor> records;
                    DataAccessor data;
                    try (DataScanner res = scan(manager, "SELECT n1 FROM mytable where id=?", Arrays.asList(entry.getKey()))) {
                        records = res.consume();
                        data = records.get(0);
                    }
                    assertEquals(1, records.size());

                    if (!entry.getValue().equals(data.get("n1"))) {
                        System.out.println("expected value " + data.get("n1") + ", but got " + Long.valueOf(entry.getValue()) + " for key " + entry.getKey());
                        erroredKeys.add(entry.getKey());
                    }
                }
                assertTrue(erroredKeys.isEmpty());

                TableManagerStats stats = server.getManager().getTableSpaceManager(TableSpace.DEFAULT).getTableManager("mytable").getStats();
                System.out.println("stats::tablesize:" + stats.getTablesize());
                System.out.println("stats::dirty records:" + stats.getDirtyrecords());
                System.out.println("stats::unload count:" + stats.getUnloadedPagesCount());
                System.out.println("stats::load count:" + stats.getLoadedPagesCount());
                System.out.println("stats::buffers used mem:" + stats.getBuffersUsedMemory());

//                assertTrue(stats.getUnloadedPagesCount() > 0);
                assertEquals(TABLESIZE, stats.getTablesize());
            } finally {
                threadPool.shutdown();
                threadPool.awaitTermination(1, TimeUnit.MINUTES);
            }
        }

        // restart and recovery
        try (Server server = new Server(serverConfiguration)) {
            server.start();
            server.waitForTableSpaceBoot(TableSpace.DEFAULT, 300000, true);
            DBManager manager = server.getManager();
            List<String> erroredKeys = new ArrayList<>();
            for (Map.Entry<String, Long> entry : expectedValue.entrySet()) {
                List<DataAccessor> records;
                DataAccessor data;
                try (DataScanner res = scan(manager, "SELECT n1 FROM mytable where id=?", Arrays.asList(entry.getKey()))) {
                    records = res.consume();
                    data = records.get(0);
                }
                assertEquals(1, records.size());

                if (!entry.getValue().equals(data.get("n1"))) {
                    System.out.println("expected value " + data.get("n1") + ", but got " + Long.valueOf(entry.getValue()) + " for key " + entry.getKey());
                    erroredKeys.add(entry.getKey());
                }
            }
            assertTrue(erroredKeys.isEmpty());
        }
    }
}
