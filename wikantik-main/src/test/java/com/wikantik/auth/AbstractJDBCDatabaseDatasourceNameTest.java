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
package com.wikantik.auth;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link AbstractJDBCDatabase#datasourceName(Properties)}.
 *
 * <p>Spec decision 4: a blank {@code wikantik.datasource} value must behave exactly
 * like an absent one for every reader.</p>
 */
class AbstractJDBCDatabaseDatasourceNameTest {

    @Test
    void absentPropertyReturnsDefault() {
        final Properties props = new Properties();

        assertEquals( AbstractJDBCDatabase.DEFAULT_DATASOURCE, AbstractJDBCDatabase.datasourceName( props ) );
    }

    @Test
    void blankPropertyReturnsDefault() {
        final Properties props = new Properties();
        props.setProperty( AbstractJDBCDatabase.PROP_DATASOURCE, "" );

        assertEquals( AbstractJDBCDatabase.DEFAULT_DATASOURCE, AbstractJDBCDatabase.datasourceName( props ) );
    }

    @Test
    void whitespaceOnlyPropertyReturnsDefault() {
        final Properties props = new Properties();
        props.setProperty( AbstractJDBCDatabase.PROP_DATASOURCE, "   " );

        assertEquals( AbstractJDBCDatabase.DEFAULT_DATASOURCE, AbstractJDBCDatabase.datasourceName( props ) );
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        final Properties props = new Properties();
        props.setProperty( AbstractJDBCDatabase.PROP_DATASOURCE, "  x  " );

        assertEquals( "x", AbstractJDBCDatabase.datasourceName( props ) );
    }

    @Test
    void nonBlankPropertyIsReturnedTrimmed() {
        final Properties props = new Properties();
        props.setProperty( AbstractJDBCDatabase.PROP_DATASOURCE, "jdbc/CustomDatabase" );

        assertEquals( "jdbc/CustomDatabase", AbstractJDBCDatabase.datasourceName( props ) );
    }
}
