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

package com.wikantik.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Properties;

/**
 * Offline coverage for {@link MailUtil}: asserts the mail properties resolve to the values the
 * bundled test configuration declares, without opening a socket.
 *
 * <p>This class deliberately sends nothing. {@link MailUtil} caches the resolved sender address
 * in a static field for the lifetime of the JVM, so a send from here would pin the "From"
 * address for every later test sharing the surefire fork — including
 * {@link MailUtilSmtpKeepAliveTest}, whose relay rejects mail from any unverified sender. The
 * real send lives there, gated on a configured relay.
 */
class MailUtilTest  {
    
    static Properties m_props = new Properties();

    @BeforeAll
    static void setUp() {
        m_props = PropertyReader.getCombinedProperties( PropertyReader.CUSTOM_WIKANTIK_CONFIG );
    }

    /**
     * Verifies that the properties loaded by tests/etc/wikantik.properties are the ones we expect.
     * Three of them (account, password, jndi name) are commented out, so we expect null.
     */
    @Test
    void testProperties() {
        Assertions.assertEquals( "127.0.0.1",                   m_props.getProperty( MailUtil.PROP_MAIL_HOST ) );
        Assertions.assertEquals( "25",                          m_props.getProperty( MailUtil.PROP_MAIL_PORT ) );
        Assertions.assertEquals( "Wikantik <Wikantik@localhost>", m_props.getProperty( MailUtil.PROP_MAIL_SENDER ) );
        Assertions.assertNull( m_props.getProperty( MailUtil.PROP_MAIL_ACCOUNT ) );
        Assertions.assertNull( m_props.getProperty( MailUtil.PROP_MAIL_PASSWORD ) );
        Assertions.assertNull( m_props.getProperty( MailUtil.PROP_MAIL_JNDI_NAME ) );
    }

}
