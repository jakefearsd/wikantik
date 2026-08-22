/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
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
package com.wikantik.jdbc;

import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Transaction helpers for the hand-rolled JDBC write paths.
 *
 * <p>{@link JdbcSupport#inTransaction} is the preferred way to run a transaction, but it is a
 * base class, so classes that already extend something else cannot use it. Those write their
 * own begin/commit/rollback, and this is the one piece they should not each re-implement.
 *
 * @see JdbcSupport#inTransaction
 */
public final class Transactions {

    private Transactions() {
    }

    /**
     * Rolls {@code conn} back, swallowing a failure of the rollback itself.
     *
     * <p>Two rules this encodes, both learned from real defects in this codebase:
     *
     * <ul>
     *   <li>Roll back explicitly rather than closing a connection with its transaction still
     *       open. What happens then is pool-defined — DBCP2, which this application deploys,
     *       defaults {@code rollbackOnReturn} to true, but that is configured nowhere in the
     *       repo and a different pool may commit instead.</li>
     *   <li>Never let the rollback's own failure replace the exception that caused it. The
     *       original is what explains the problem; a "connection already closed" thrown from
     *       the cleanup path is noise that hides it.</li>
     * </ul>
     *
     * <p>Callers must not invoke this on a connection still in auto-commit mode: rolling back
     * outside a transaction is an error in JDBC. Gate it on whatever flag decided to call
     * {@code setAutoCommit( false )} in the first place.
     *
     * @param conn    the connection whose transaction should be discarded.
     * @param cause   the failure that triggered the rollback, for the log line. {@link Throwable}
     *                rather than {@link Exception} so an {@link Error} (e.g. {@code AssertionError}
     *                from a test) can be logged here too — {@link Jdbc#inTransaction} rolls back on
     *                both.
     * @param log     the caller's logger, so the message is attributed to the caller.
     * @param context short description of the operation, e.g. {@code "save(profile)"}.
     */
    public static void rollbackQuietly( final Connection conn, final Throwable cause,
                                        final Logger log, final String context ) {
        try {
            conn.rollback();
        } catch ( final SQLException rollbackEx ) {
            log.warn( "{}: rollback failed after '{}': {}",
                      context, cause.getMessage(), rollbackEx.getMessage(), rollbackEx );
        }
    }
}
