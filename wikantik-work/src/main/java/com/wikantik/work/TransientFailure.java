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
package com.wikantik.work;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeoutException;

/**
 * Whether a failure may go away by itself, as opposed to one that will repeat for the same input. The default is
 * "transient": an event is given up on only when the failure is provably about the data, so an unforeseen
 * infrastructure failure can never lose a notification. Poison is (a) a non-SQL exception thrown while planning, and
 * (b) an SQLException whose SQLState is a data, constraint or syntax error ({@link #DETERMINISTIC_STATES}). Anything
 * that names the directory, the connection or the clock is transient: {@link WorkUnavailableException}, the JDBC
 * transient and recoverable families, I/O, timeouts, an exhausted pool (an SQLException with no SQLState, often caused
 * by a NoSuchElementException), any other SQLState, or an interrupted thread.
 */
final class TransientFailure {

    /** SQLSTATE classes that are about the statement or the data: cardinality/integrity/syntax; they repeat. */
    private static final String[] DETERMINISTIC_STATES = { "21", "22", "23", "42", "0A" };
    private static final int MAX_DEPTH = 16;

    private TransientFailure() {
    }

    static boolean is( final Throwable failure ) {
        if ( Thread.currentThread().isInterrupted() ) {
            return true;
        }
        SQLException sql = null;
        Throwable t = failure;
        for ( int depth = 0; t != null && depth < MAX_DEPTH; depth++, t = t.getCause() ) {
            if ( t instanceof WorkUnavailableException || t instanceof SQLTransientException
                    || t instanceof SQLRecoverableException || t instanceof SQLNonTransientConnectionException
                    || t instanceof InterruptedException || t instanceof IOException
                    || t instanceof TimeoutException || t instanceof NoSuchElementException ) {
                return true;
            }
            if ( sql == null && t instanceof SQLException s ) {
                sql = s;
            }
        }
        return sql != null && !deterministic( sql.getSQLState() );
    }

    private static boolean deterministic( final String state ) {
        if ( state == null ) {
            return false;
        }
        for ( final String prefix : DETERMINISTIC_STATES ) {
            if ( state.startsWith( prefix ) ) {
                return true;
            }
        }
        return false;
    }
}
