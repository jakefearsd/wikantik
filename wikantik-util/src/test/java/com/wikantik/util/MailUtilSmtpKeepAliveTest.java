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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import jakarta.mail.MessagingException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Properties;

/**
 * Sends one real message through the configured SMTP relay on every qualifying run, so the
 * relay credential does not expire through disuse. Brevo deactivates SMTP keys that go
 * unused for long enough; when that happens password-reset and registration mail stop
 * working with no code change to blame, so a trickle of deliberate traffic is cheaper than
 * the outage.
 *
 * <p>The test is opt-in and skips itself unless {@code MAIL_SMTP_HOST} is set, matching the
 * "empty = disabled" convention the project's {@code .env} already uses for mail. Populate
 * the environment straight from that file before running the suite:
 *
 * <pre>set -a; source .env; set +a</pre>
 *
 * <p>The variables are deliberately <em>not</em> prefixed {@code WIKANTIK_}: {@code
 * bin/agent-build.sh} unsets every {@code WIKANTIK_*} variable in the build it spawns, which
 * would silently disable the send for agent-driven builds — exactly the runs least likely to
 * be noticed going quiet.
 *
 * <p>When a relay is configured the send is asserted for real: a rejected credential fails
 * the build. A soft pass here would let the key lapse unnoticed, which is the outcome this
 * test exists to prevent. See {@code MailUtilTest} for the offline unit coverage.
 */
class MailUtilSmtpKeepAliveTest {

    private static final String ENV_HOST      = "MAIL_SMTP_HOST";
    private static final String ENV_PORT      = "MAIL_SMTP_PORT";
    private static final String ENV_ACCOUNT   = "MAIL_SMTP_ACCOUNT";
    private static final String ENV_PASSWORD  = "MAIL_SMTP_PASSWORD";
    private static final String ENV_FROM      = "MAIL_FROM";
    private static final String ENV_RECIPIENT = "MAIL_KEEPALIVE_TO";

    /** Where the keep-alive lands when {@code MAIL_KEEPALIVE_TO} is not set. */
    private static final String DEFAULT_RECIPIENT = "jakefear@gmail.com";

    /** Fixed prefix so the mail filters to a label client-side and greps out of relay logs. */
    static final String SUBJECT_PREFIX = "[wikantik-smtp-keepalive]";

    @Test
    @EnabledIfEnvironmentVariable( named = ENV_HOST, matches = ".+" )
    void sendsKeepAliveMailThroughTheConfiguredRelay() {
        final Properties props = new Properties();
        props.setProperty( MailUtil.PROP_MAIL_HOST,     required( ENV_HOST ) );
        props.setProperty( MailUtil.PROP_MAIL_PORT,     optional( ENV_PORT, "587" ) );
        props.setProperty( MailUtil.PROP_MAIL_ACCOUNT,  required( ENV_ACCOUNT ) );
        props.setProperty( MailUtil.PROP_MAIL_PASSWORD, required( ENV_PASSWORD ) );
        props.setProperty( MailUtil.PROP_MAIL_SENDER,   required( ENV_FROM ) );
        props.setProperty( MailUtil.PROP_MAIL_STARTTLS, "true" );

        final String recipient = optional( ENV_RECIPIENT, DEFAULT_RECIPIENT );
        final String sentAt = Instant.now().toString();
        final String subject = SUBJECT_PREFIX + " " + hostname() + " " + sentAt;

        try {
            MailUtil.sendMessage( props, recipient, subject, body( recipient, sentAt ) );
        } catch( final MessagingException e ) {
            Assertions.fail( "The SMTP relay rejected the keep-alive message. The credential in "
                             + ENV_PASSWORD + " may have expired or been revoked — check the relay "
                             + "dashboard before assuming this is a transient network fault.", e );
        }
    }

    /**
     * Reads a variable that must be present once a relay host is configured. A half-populated
     * environment fails loudly rather than quietly falling back to localhost:25, which would
     * send nothing and still report success.
     */
    private static String required( final String name ) {
        final String value = System.getenv( name );
        Assertions.assertTrue( value != null && !value.isBlank(),
                               name + " must be set when " + ENV_HOST + " is configured. Load the "
                               + "whole mail block at once with: set -a; source .env; set +a" );
        return value.trim();
    }

    private static String optional( final String name, final String fallback ) {
        final String value = System.getenv( name );
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch( final UnknownHostException e ) {
            // Only decorates the subject line; an unresolvable local hostname must not stop the send.
            return "unknown-host";
        }
    }

    private static String body( final String recipient, final String sentAt ) {
        return """
               This is an automated keep-alive message from the Wikantik test suite.

               It exists so the SMTP relay credential sees regular traffic and is not \
               deactivated for disuse. Nothing is wrong, and no action is needed.

               Recipient: %s
               Sent at:   %s
               Origin:    %s (MailUtilSmtpKeepAliveTest)
               """.formatted( recipient, sentAt, hostname() );
    }

}
