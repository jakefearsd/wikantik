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
package com.wikantik.work.runtime;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The adapter is tested against a stubbed sender only: nothing here reaches MailUtil, a session or a relay. */
class WikiWorkMailerTest {

    @Test
    void aMessagingExceptionBecomesAnIOException() {
        final WikiWorkMailer mailer = new WikiWorkMailer( Properties::new, ( props, to, subject, text ) -> {
            throw new MessagingException( "relay refused" );
        } );
        final IOException e = assertThrows( IOException.class, () -> mailer.send( "a@example.com", "s", "t" ) );
        assertEquals( MessagingException.class, e.getCause().getClass() );
    }

    @Test
    void itPassesTheWikisMailProperties() throws IOException {
        final Properties wiki = new Properties();
        final List< String > seen = new ArrayList<>();
        final List< Properties > props = new ArrayList<>();
        final WikiWorkMailer mailer = new WikiWorkMailer( () -> wiki, ( p, to, subject, text ) -> {
            props.add( p );
            seen.add( to + "|" + subject + "|" + text );
        } );
        mailer.send( "a@example.com", "Work: 1 update on X-1", "body" );
        assertSame( wiki, props.get( 0 ) );
        assertEquals( List.of( "a@example.com|Work: 1 update on X-1|body" ), seen );
    }
}
