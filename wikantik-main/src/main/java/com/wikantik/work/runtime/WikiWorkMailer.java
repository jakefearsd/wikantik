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

import com.wikantik.api.core.Engine;
import com.wikantik.core.subsystem.CoreSubsystemBridge;
import com.wikantik.util.MailUtil;
import com.wikantik.work.WorkMailer;
import jakarta.mail.MessagingException;

import java.io.IOException;
import java.util.Properties;
import java.util.function.Supplier;

/** Sends work notification mail through the wiki's one mail facility ({@link MailUtil}) and its mail settings. */
public final class WikiWorkMailer implements WorkMailer {

    /** The send function; {@code MailUtil::sendMessage} in production. */
    @FunctionalInterface
    interface Sender {
        void send( Properties props, String to, String subject, String text ) throws MessagingException;
    }

    private final Supplier< Properties > properties;
    private final Sender sender;

    public WikiWorkMailer( final Engine engine ) {
        this( () -> CoreSubsystemBridge.fromLegacyEngine( engine ).properties().asProperties(), MailUtil::sendMessage );
    }

    WikiWorkMailer( final Supplier< Properties > properties, final Sender sender ) {
        this.properties = properties;
        this.sender = sender;
    }

    @Override
    public void send( final String to, final String subject, final String text ) throws IOException {
        try {
            sender.send( properties.get(), to, subject, text );
        } catch ( final MessagingException e ) {
            throw new IOException( "The mail could not be sent: " + e.getClass().getSimpleName(), e );
        }
    }
}
