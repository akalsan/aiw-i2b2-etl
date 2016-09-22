package edu.emory.cci.aiw.i2b2etl.util;

/*
 * #%L
 * AIW i2b2 ETL
 * %%
 * Copyright (C) 2012 - 2015 Emory University
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.arp.javautil.sql.ConnectionSpec;

/**
 * Inserts a record into a database using prepared statements in batch mode. The
 * actual batch inserts occur in a separate thread.
 *
 * @author Andrew Post
 */
public abstract class RecordHandler<E> implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(RecordHandler.class.getName());
    private static final String SQL_RUNNER_BATCH_SIZE_PROPERTY = "aiw.i2b2Etl.sqlRunner.batchSize";
    private static final String SQL_RUNNER_COMMIT_SIZE_PROPERTY = "aiw.i2b2Etl.sqlRunner.commitSize";

    private final int batchSize = Integer.getInteger(SQL_RUNNER_BATCH_SIZE_PROPERTY, 1000);
    private final int commitSize = Integer.getInteger(SQL_RUNNER_COMMIT_SIZE_PROPERTY, 10000);

    private int commitCounter;
    private int counter;
    private volatile PreparedStatement ps;
    private final String statement;
    private Connection cn;
    private final Timestamp importTimestamp;
    private final boolean commit;
    private final List<E> records;
    private final int maxTries;
    private ConnectionSpec connSpec;

    public RecordHandler(Connection connection, String statement) throws SQLException {
        this(connection, statement, true);
    }

    public RecordHandler(Connection connection, String statement, boolean commit) throws SQLException {
        if (connection == null) {
            throw new IllegalArgumentException("connection cannot be null");
        }
        if (statement == null) {
            throw new IllegalArgumentException("statement cannot be null");
        }
        this.cn = connection;
        this.statement = statement;
        this.importTimestamp = new Timestamp(System.currentTimeMillis());
        this.commit = commit;
        this.records = new ArrayList<>();
        this.maxTries = 1;
        this.counter = 0;
        this.commitCounter = 0;
        init();
    }

    public RecordHandler(ConnectionSpec connSpec, String statement) throws SQLException {
        if (connSpec == null) {
            throw new IllegalArgumentException("connection cannot be null");
        }
        if (statement == null) {
            throw new IllegalArgumentException("statement cannot be null");
        }
        this.connSpec = connSpec;
        this.statement = statement;
        this.importTimestamp = new Timestamp(System.currentTimeMillis());
        this.commit = true;
        this.records = new ArrayList<>();
        this.maxTries = 3;
        this.counter = 0;
        this.commitCounter = 0;
        init();
    }

    public void insert(E record) throws SQLException {
        if (record != null) {
            try {
                this.records.add(record);
                this.counter++;
                this.commitCounter++;
                setParameters(this.ps, record);
                this.ps.addBatch();
                if (this.counter >= this.batchSize) {
                    executeBatch();
                }
                if (this.commitCounter >= this.commitSize) {
                    commit();
                }
            } catch (SQLException e) {
                rollback(e);
                if (this.ps != null) {
                    try {
                        this.ps.close();
                    } catch (SQLException sqle) {
                        e.addSuppressed(sqle);
                    }
                }
                if (!this.records.isEmpty() && this.connSpec != null) {
                    retry(e, false);
                }
            }
        }
    }

    protected abstract void setParameters(PreparedStatement statement, E record) throws SQLException;

    protected Connection getConnection() {
        return this.cn;
    }

    @Override
    public void close() throws SQLException {
        SQLException exceptionThrown = null;
        if (this.ps != null) {
            try {
                try {
                    executeBatch();
                    commit();
                } catch (SQLException ex) {
                    rollback(ex);
                    exceptionThrown = ex;
                    if (!this.records.isEmpty() && this.connSpec != null) {
                        retry(exceptionThrown, true);
                    }
                }
                this.ps.close();
                this.ps = null;
            } finally {
                if (this.ps != null) {
                    try {
                        this.ps.close();
                    } catch (SQLException ignore) {
                        if (exceptionThrown != null) {
                            exceptionThrown.addSuppressed(ignore);
                        } else {
                            exceptionThrown = ignore;
                        }
                    }
                }
                if (this.connSpec != null && this.cn != null) {
                    try {
                        this.cn.close();
                    } catch (SQLException ignore) {
                        if (exceptionThrown != null) {
                            exceptionThrown.addSuppressed(ignore);
                        } else {
                            exceptionThrown = ignore;
                        }
                    }
                }
            }
        }
        if (exceptionThrown != null) {
            throw exceptionThrown;
        }
    }

    protected Timestamp importTimestamp() {
        return this.importTimestamp;
    }

    private void init() throws SQLException {
        if (this.connSpec != null) {
            this.cn = this.connSpec.getOrCreate();
        }
        this.ps = this.cn.prepareStatement(this.statement);
        this.counter = 0;
        this.commitCounter = 0;
    }

    private void executeBatch() throws SQLException {
        if (counter > 0) {
            ps.executeBatch();
            counter = 0;
            if (LOGGER.isLoggable(Level.FINER)) {
                LOGGER.log(Level.FINER, "Batch executed successfully");
            }
            ps.clearBatch();
            ps.clearParameters();
        }
    }

    private void commit() throws SQLException {
        if (commitCounter > 0) {
            if (commit) {
                cn.commit();
            }
            commitCounter = 0;
            records.clear();
        }
    }

    private void retry(SQLException e, boolean inClose) throws SQLException {
        LOGGER.log(Level.WARNING, "Retrying after database error", e);
        int tried = 0;
        while (++tried <= this.maxTries) {
            try {
                reconnectAndReplay(inClose);
                break;
            } catch (SQLException ex) {
                LOGGER.log(Level.WARNING, "Retrying failed");
                e.addSuppressed(ex);
                if (tried == this.maxTries) {
                    LOGGER.log(Level.SEVERE, "Giving up after " + tried + " tries", ex);
                    throw e;
                }
            }
        }
    }

    private void reconnectAndReplay(boolean inClose) throws SQLException {
        init();
        for (E record : this.records) {
            setParameters(this.ps, record);
            this.ps.addBatch();
            this.counter++;
            this.commitCounter++;
        }
        if (!this.records.isEmpty()) {
            try {
                if (inClose) {
                    executeBatch();
                    commit();
                } else {
                    if (this.counter >= this.batchSize) {
                        executeBatch();
                    }
                    if (this.commitCounter >= this.commitSize) {
                        commit();
                    }
                }
            } catch (SQLException ex) {
                rollback(ex);
                throw ex;
            }
        }
    }

    private void rollback(Throwable throwable) {
        if (commit) {
            try {
                this.cn.rollback();
            } catch (SQLException ignore) {
                throwable.addSuppressed(ignore);
            }
        }
    }
}
