/*
 * Copyright 2019 Aiven Oy and jdbc-connector-for-apache-kafka project contributors
 * Copyright 2015 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.aiven.connect.jdbc.source;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.kafka.connect.connector.ConnectorContext;
import org.apache.kafka.connect.errors.ConnectException;

import io.aiven.connect.jdbc.dialect.DatabaseDialect;
import io.aiven.connect.jdbc.util.ConnectionProvider;
import io.aiven.connect.jdbc.util.TableId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread that monitors the database for changes to the set of tables in the database that this
 * connector should load data from.
 */
public class TableMonitorThread extends Thread {
    private static final Logger log = LoggerFactory.getLogger(TableMonitorThread.class);

    private final DatabaseDialect dialect;
    private final ConnectionProvider connectionProvider;
    private final ConnectorContext context;
    private final CountDownLatch shutdownLatch;
    private final long pollMs;
    private Set<String> whitelist;
    private Set<String> blacklist;
    private List<TableId> tables;
    private Map<String, List<TableId>> duplicates;

    public TableMonitorThread(final DatabaseDialect dialect,
                              final ConnectionProvider connectionProvider,
                              final ConnectorContext context,
                              final long pollMs,
                              final Set<String> whitelist,
                              final Set<String> blacklist
    ) {
        this.dialect = dialect;
        this.connectionProvider = connectionProvider;
        this.context = context;
        this.shutdownLatch = new CountDownLatch(1);
        this.pollMs = pollMs;
        this.whitelist = whitelist;
        this.blacklist = blacklist;
        this.tables = null;

    }

    @Override
    public void run() {
        log.info("Starting thread to monitor tables.");
        while (shutdownLatch.getCount() > 0) {
            try {
                if (updateTables()) {
                    context.requestTaskReconfiguration();
                }
            } catch (final Exception e) {
                context.raiseError(e);
                throw e;
            }

            try {
                log.debug("Waiting {} ms to check for changed.", pollMs);
                final boolean shuttingDown = shutdownLatch.await(pollMs, TimeUnit.MILLISECONDS);
                if (shuttingDown) {
                    return;
                }
            } catch (final InterruptedException e) {
                log.error("Unexpected InterruptedException, ignoring: ", e);
            }
        }
    }

    public synchronized List<TableId> tables() {
        //TODO: Timeout should probably be user-configurable or class-level constant
        final long timeout = 10000L;
        final long started = System.currentTimeMillis();
        long now = started;
        while (tables == null && now - started < timeout) {
            try {
                wait(timeout - (now - started));
            } catch (final InterruptedException e) {
                // Ignore
            }
            now = System.currentTimeMillis();
        }
        if (tables == null) {
            throw new ConnectException("Tables could not be updated quickly enough.");
        }
        if (!duplicates.isEmpty()) {
            final String configText;
            if (whitelist != null) {
                configText = "'" + JdbcSourceConnectorConfig.TABLE_WHITELIST_CONFIG + "'";
            } else if (blacklist != null) {
                configText = "'" + JdbcSourceConnectorConfig.TABLE_BLACKLIST_CONFIG + "'";
            } else {
                configText = "'" + JdbcSourceConnectorConfig.TABLE_WHITELIST_CONFIG + "' or '"
                    + JdbcSourceConnectorConfig.TABLE_BLACKLIST_CONFIG + "'";
            }
            final String msg = "The connector uses the unqualified table name as the topic name and has "
                + "detected duplicate unqualified table names. This could lead to mixed data types in "
                + "the topic and downstream processing errors. To prevent such processing errors, the "
                + "JDBC Source connector fails to start when it detects duplicate table name "
                + "configurations. Update the connector's " + configText + " config to include exactly "
                + "one table in each of the tables listed below.\n\t";
            throw new ConnectException(msg + duplicates.values());
        }
        return tables;
    }

    public void shutdown() {
        log.info("Shutting down thread monitoring tables.");
        shutdownLatch.countDown();
    }

    private synchronized boolean updateTables() {
        final List<TableId> tables;
        try {
            tables = dialect.tableIds(connectionProvider.getConnection());
            log.debug("Got the following tables: " + Arrays.toString(tables.toArray()));
        } catch (final SQLException e) {
            log.error(
                "Error while trying to get updated table list, ignoring and waiting for next table poll"
                    + " interval",
                e
            );
            connectionProvider.close();
            return false;
        }

        final List<TableId> filteredTables = new ArrayList<>(tables.size());
        if (whitelist != null) {
            for (final TableId table : tables) {
                final String fqn1 = dialect.expressionBuilder().append(table, false).toString();
                final String fqn2 = dialect.expressionBuilder().append(table, true).toString();
                if (whitelist.contains(fqn1) || whitelist.contains(fqn2)
                    || whitelist.contains(table.tableName())) {
                    filteredTables.add(table);
                }
            }
        } else if (blacklist != null) {
            for (final TableId table : tables) {
                final String fqn1 = dialect.expressionBuilder().append(table, false).toString();
                final String fqn2 = dialect.expressionBuilder().append(table, true).toString();
                if (!(blacklist.contains(fqn1) || blacklist.contains(fqn2)
                    || blacklist.contains(table.tableName()))) {
                    filteredTables.add(table);
                }
            }
        } else {
            filteredTables.addAll(tables);
        }

        if (!filteredTables.equals(this.tables)) {
            log.info(
                "After filtering the tables are: {}",
                dialect.expressionBuilder()
                    .appendList()
                    .delimitedBy(",")
                    .of(filteredTables)
            );
            final Map<String, List<TableId>> duplicates = filteredTables.stream()
                .collect(Collectors.groupingBy(TableId::tableName))
                .entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            this.duplicates = duplicates;
            final List<TableId> previousTables = this.tables;
            this.tables = filteredTables;
            notifyAll();
            // Only return true if the table list wasn't previously null, i.e. if this was not the
            // first table lookup
            return previousTables != null;
        }

        return false;
    }
}
