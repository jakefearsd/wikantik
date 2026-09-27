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

import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.naming.NamingException;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline coverage for the {@link MailUtil} paths {@link MailUtilTest} deliberately leaves
 * untouched: {@link MailUtil#sendMessage}, sender-resolution precedence, standalone-session
 * property mapping, and the JNDI failure path. Every send goes to a hand-rolled
 * {@link FakeSmtpServer} on loopback — never a real relay (that lives in
 * {@link MailUtilSmtpKeepAliveTest}).
 *
 * <p>{@code MailUtil}'s {@code c_fromAddress}/{@code c_useJndi} statics are cached for the JVM
 * lifetime, so every test here resets them via {@link MailUtil#resetCachedState()} before and
 * after running — otherwise a resolved sender or a JNDI-failure flag from one test would leak
 * into the next test sharing this surefire fork, including {@code MailUtilSmtpKeepAliveTest}.
 */
class MailUtilSendTest {

    @BeforeEach
    void resetBefore() {
        MailUtil.resetCachedState();
    }

    @AfterEach
    void resetAfter() {
        MailUtil.resetCachedState();
    }

    private static Properties baseProps( final FakeSmtpServer server ) {
        final Properties props = new Properties();
        props.setProperty( MailUtil.PROP_MAIL_HOST, "127.0.0.1" );
        props.setProperty( MailUtil.PROP_MAIL_PORT, String.valueOf( server.port() ) );
        props.setProperty( MailUtil.PROP_MAIL_STARTTLS, "false" );
        props.setProperty( MailUtil.PROP_MAIL_SENDER, "Wikantik Test <test@wikantik.example>" );
        props.setProperty( MailUtil.PROP_MAIL_TIMEOUT, "4000" );
        props.setProperty( MailUtil.PROP_MAIL_CONNECTION_TIMEOUT, "4000" );
        return props;
    }

    /** Reads the private JVM-lifetime cache directly, to check resolution precedence without a send. */
    private static String cachedFromAddress() throws ReflectiveOperationException {
        final Field field = MailUtil.class.getDeclaredField( "c_fromAddress" );
        field.setAccessible( true );
        return ( String ) field.get( null );
    }

    // --------- sendMessage over the standalone session (JNDI unavailable in a unit-test JVM) ----

    @Test
    void sendMessageDeliversEnvelopeAndUtf8Body() throws Exception {
        try( final FakeSmtpServer server = new FakeSmtpServer() ) {
            final Properties props = baseProps( server );

            MailUtil.sendMessage( props, "Recipient <rcpt@wikantik.example>", "Test subject", "hello world" );

            assertTrue( server.lastMailFrom().contains( "test@wikantik.example" ) );
            assertEquals( 1, server.lastRcptTo().size() );
            assertTrue( server.lastRcptTo().get( 0 ).contains( "rcpt@wikantik.example" ) );

            final String data = server.lastData();
            assertTrue( data.contains( "Subject: Test subject" ), data );
            assertTrue( data.contains( "hello world" ), data );
            assertTrue( data.toUpperCase( Locale.ROOT ).contains( "CHARSET=UTF-8" ), data );
        }
    }

    @Test
    void sendMessageAuthenticatesWhenAccountAndPasswordAreSet() throws Exception {
        try( final FakeSmtpServer server = new FakeSmtpServer() ) {
            final Properties props = baseProps( server );
            props.setProperty( MailUtil.PROP_MAIL_ACCOUNT, "smtp-user" );
            props.setProperty( MailUtil.PROP_MAIL_PASSWORD, "smtp-pass" );

            MailUtil.sendMessage( props, "rcpt@wikantik.example", "Authenticated send", "body" );

            assertTrue( server.lastMailFrom().contains( "test@wikantik.example" ) );
            assertEquals( 1, server.lastRcptTo().size() );
        }
    }

    @Test
    void sendMessageThrowsMessagingExceptionWhenRelayRejectsRecipient() throws Exception {
        try( final FakeSmtpServer server = new FakeSmtpServer() ) {
            server.rejectRecipientsWith( "550 5.1.1 Mailbox unavailable" );
            final Properties props = baseProps( server );

            assertThrows( MessagingException.class,
                           () -> MailUtil.sendMessage( props, "rcpt@wikantik.example", "subject", "body" ) );
        }
    }

    // --------- setSenderEmailAddress precedence -------------------------------------------------

    @Test
    void setSenderEmailAddressTrimsAndUsesPropertyValueWhenNoSession() throws Exception {
        final Properties props = new Properties();
        props.setProperty( MailUtil.PROP_MAIL_SENDER, "  Trimmed Sender <trimmed@wikantik.example>  " );

        MailUtil.setSenderEmailAddress( null, props );

        assertEquals( "Trimmed Sender <trimmed@wikantik.example>", cachedFromAddress() );
    }

    @Test
    void setSenderEmailAddressFallsBackToDefaultSenderWhenPropertyAbsent() throws Exception {
        MailUtil.setSenderEmailAddress( null, new Properties() );

        assertEquals( MailUtil.DEFAULT_SENDER, cachedFromAddress() );
    }

    @Test
    void setSenderEmailAddressPrefersJndiSessionPropertyOverProperties() throws Exception {
        final Properties sessionProps = new Properties();
        sessionProps.setProperty( MailUtil.PROP_MAIL_SENDER, "from-jndi@wikantik.example" );
        final Session jndiSession = Session.getInstance( sessionProps );

        final Properties fallbackProps = new Properties();
        fallbackProps.setProperty( MailUtil.PROP_MAIL_SENDER, "from-properties@wikantik.example" );

        MailUtil.setSenderEmailAddress( jndiSession, fallbackProps );

        assertEquals( "from-jndi@wikantik.example", cachedFromAddress() );
    }

    @Test
    void setSenderEmailAddressFallsBackToPropertiesWhenJndiSessionHasNoMailFrom() throws Exception {
        final Session jndiSession = Session.getInstance( new Properties() );

        final Properties fallbackProps = new Properties();
        fallbackProps.setProperty( MailUtil.PROP_MAIL_SENDER, "from-properties@wikantik.example" );

        MailUtil.setSenderEmailAddress( jndiSession, fallbackProps );

        assertEquals( "from-properties@wikantik.example", cachedFromAddress() );
    }

    // --------- getStandaloneMailSession property mapping ------------------------------------------

    @Test
    void getStandaloneMailSessionMapsCoreSmtpProperties() {
        final Properties props = new Properties();
        props.setProperty( MailUtil.PROP_MAIL_HOST, "mail.example.com" );
        props.setProperty( MailUtil.PROP_MAIL_PORT, "2525" );
        props.setProperty( MailUtil.PROP_MAIL_TIMEOUT, "9000" );
        props.setProperty( MailUtil.PROP_MAIL_CONNECTION_TIMEOUT, "8000" );

        final Session session = MailUtil.getStandaloneMailSession( props );

        assertEquals( "mail.example.com", session.getProperty( MailUtil.PROP_MAIL_HOST ) );
        assertEquals( "2525", session.getProperty( MailUtil.PROP_MAIL_PORT ) );
        assertEquals( "9000", session.getProperty( MailUtil.PROP_MAIL_TIMEOUT ) );
        assertEquals( "8000", session.getProperty( MailUtil.PROP_MAIL_CONNECTION_TIMEOUT ) );
        // starttls wasn't supplied by the caller, so — unlike the computed-default local variable
        // that only feeds the debug log line — the Session's own property table has nothing set.
        assertNull( session.getProperty( MailUtil.PROP_MAIL_STARTTLS ) );
        // no account -> no auth requested
        assertNull( session.getProperty( "mail.smtp.auth" ) );
    }

    @Test
    void getStandaloneMailSessionResolvesDefaultsForLoggingButLeavesUnsuppliedSessionPropertiesUnset() {
        // MailUtil still computes host/port/timeout/starttls defaults for its debug log line even
        // when nothing is configured, but only caller-supplied "mail.smtp*" keys get copied onto
        // the actual JavaMail Session — so an empty Properties yields an empty Session.
        final Session session = MailUtil.getStandaloneMailSession( new Properties() );

        assertNotNull( session );
        assertNull( session.getProperty( MailUtil.PROP_MAIL_HOST ) );
        assertNull( session.getProperty( "mail.smtp.auth" ) );
    }

    @Test
    void getStandaloneMailSessionCopiesSmtpsPrefixedKeysVerbatimRatherThanTranslatingThem() {
        final Properties props = new Properties();
        props.setProperty( MailUtil.PROP_MAILS_HOST, "smtps.example.com" );
        props.setProperty( MailUtil.PROP_MAILS_PORT, "465" );

        final Session session = MailUtil.getStandaloneMailSession( props );

        // "mail.smtps.*" starts with the "mail.smtp" prefix MailUtil filters on, so it is copied
        // onto the Session under its own key — not translated to the plain "mail.smtp.*" key,
        // which therefore stays unset.
        assertEquals( "smtps.example.com", session.getProperty( MailUtil.PROP_MAILS_HOST ) );
        assertEquals( "465", session.getProperty( MailUtil.PROP_MAILS_PORT ) );
        assertNull( session.getProperty( MailUtil.PROP_MAIL_HOST ) );
    }

    @Test
    void getStandaloneMailSessionDisablesStarttlsWhenExplicitlyFalse() {
        final Properties props = new Properties();
        props.setProperty( MailUtil.PROP_MAIL_STARTTLS, "false" );

        final Session session = MailUtil.getStandaloneMailSession( props );

        assertEquals( "false", session.getProperty( MailUtil.PROP_MAIL_STARTTLS ) );
    }

    @Test
    void getStandaloneMailSessionEnablesAuthWhenAccountIsSet() {
        final Properties props = new Properties();
        props.setProperty( MailUtil.PROP_MAIL_ACCOUNT, "smtp-user" );
        props.setProperty( MailUtil.PROP_MAIL_PASSWORD, "smtp-pass" );

        final Session session = MailUtil.getStandaloneMailSession( props );

        assertEquals( "true", session.getProperty( "mail.smtp.auth" ) );
        assertEquals( "true", session.getProperty( "mail.smtps.auth" ) );
    }

    // --------- getJNDIMailSession -----------------------------------------------------------------

    @Test
    void getJNDIMailSessionThrowsNamingExceptionWithoutAContainer() {
        // This unit test JVM has no javax.naming.spi.InitialContextFactory configured, so a real
        // container-managed lookup is unreachable — exactly the failure path MailUtil must survive.
        assertThrows( NamingException.class, () -> MailUtil.getJNDIMailSession( "mail/Session" ) );
    }

    // --------- SmtpAuthenticator --------------------------------------------------------------------

    @Test
    void smtpAuthenticatorReturnsCredentialWhenPasswordIsPresent() {
        final MailUtil.SmtpAuthenticator auth = new MailUtil.SmtpAuthenticator( "user", "pass" );

        final PasswordAuthentication result = auth.getPasswordAuthentication();

        assertNotNull( result );
        assertEquals( "user", result.getUserName() );
        assertEquals( "pass", result.getPassword() );
    }

    @Test
    void smtpAuthenticatorReturnsNullWhenPasswordIsBlank() {
        final MailUtil.SmtpAuthenticator auth = new MailUtil.SmtpAuthenticator( "user", "" );

        assertNull( auth.getPasswordAuthentication() );
    }

    @Test
    void smtpAuthenticatorDefaultsNullCredentialsToBlank() {
        final MailUtil.SmtpAuthenticator auth = new MailUtil.SmtpAuthenticator( null, null );

        assertNull( auth.getPasswordAuthentication() );
    }

}
