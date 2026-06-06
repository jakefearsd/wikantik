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
package com.wikantik.render.subsystem.spam;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.RedirectException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DefaultSpamExternalSignals} — local (non-network) checks only.
 *
 * <p><strong>checkAkismet is NOT tested here.</strong> It constructs a real
 * {@code net.thauvin.erik.akismet.Akismet} instance that makes live network calls
 * to the Akismet service. Covering it without a live API key or a dedicated
 * HTTP-stub would require either a real key (unavailable in CI) or restructuring
 * the class to accept an injectable Akismet factory — neither is in scope for
 * this coverage improvement task.</p>
 */
class DefaultSpamExternalSignalsTest {

    /** The correct UTF-8 sentinel value the filter expects. */
    private static final String UTF8_SENTINEL = "ぁ";  // ぁ

    private DefaultSpamExternalSignals signals;
    private Context context;
    private Page page;
    private HttpServletRequest request;
    private SpamChange change;

    @BeforeEach
    void setUp() {
        final Properties props = new Properties();
        // No akismet-apikey → akismet branch stays dormant
        props.setProperty( "errorpage", "RejectedMessage" );

        // stopAtFirstMatch=true → checkStrategy always throws RedirectException immediately
        signals = new DefaultSpamExternalSignals( props, true, "RejectedMessage" );

        page = mock( Page.class );
        when( page.getName() ).thenReturn( "TestPage" );

        request = mock( HttpServletRequest.class );
        when( request.getRemoteAddr() ).thenReturn( "127.0.0.1" );

        context = mock( Context.class );
        when( context.getPage() ).thenReturn( page );
        when( context.getHttpRequest() ).thenReturn( request );
        when( context.getURL( anyString(), anyString() ) ).thenReturn( "http://localhost/RejectedMessage" );

        change = new SpamChange();
        change.change = "some wiki content";
    }

    // -----------------------------------------------------------------------
    // checkBotTrap — "submit_auth" honeypot field
    // -----------------------------------------------------------------------

    @Test
    void checkBotTrap_nonEmptySubmitAuth_throwsRedirectException() {
        when( request.getParameter( "submit_auth" ) ).thenReturn( "I am a bot" );

        assertThrows( RedirectException.class,
                () -> signals.checkBotTrap( context, change ),
                "A non-empty submit_auth field should trigger the bot-trap redirect" );
    }

    @Test
    void checkBotTrap_emptySubmitAuth_doesNotThrow() {
        when( request.getParameter( "submit_auth" ) ).thenReturn( "" );

        assertDoesNotThrow( () -> signals.checkBotTrap( context, change ),
                "An empty submit_auth field must not trigger spam detection" );
    }

    @Test
    void checkBotTrap_nullSubmitAuth_doesNotThrow() {
        when( request.getParameter( "submit_auth" ) ).thenReturn( null );

        assertDoesNotThrow( () -> signals.checkBotTrap( context, change ),
                "A null submit_auth field must not trigger spam detection" );
    }

    @Test
    void checkBotTrap_noHttpRequest_doesNotThrow() {
        when( context.getHttpRequest() ).thenReturn( null );

        assertDoesNotThrow( () -> signals.checkBotTrap( context, change ),
                "With no HTTP request, the bot-trap check must be silently skipped" );
    }

    @Test
    void checkBotTrap_whitespaceOnlySubmitAuth_throwsRedirectException() {
        // A whitespace-only value is still non-empty → still a bot indicator
        when( request.getParameter( "submit_auth" ) ).thenReturn( "   " );

        assertThrows( RedirectException.class,
                () -> signals.checkBotTrap( context, change ),
                "A whitespace-only submit_auth should trigger the bot-trap (it is non-empty)" );
    }

    // -----------------------------------------------------------------------
    // checkUTF8 — "encodingcheck" hidden field
    // -----------------------------------------------------------------------

    @Test
    void checkUTF8_correctSentinel_doesNotThrow() {
        when( request.getParameter( "encodingcheck" ) ).thenReturn( UTF8_SENTINEL );

        assertDoesNotThrow( () -> signals.checkUTF8( context, change ),
                "The correct UTF-8 sentinel value must not trigger the UTF-8 trap" );
    }

    @Test
    void checkUTF8_wrongValue_throwsRedirectException() {
        when( request.getParameter( "encodingcheck" ) ).thenReturn( "garbled" );

        assertThrows( RedirectException.class,
                () -> signals.checkUTF8( context, change ),
                "A wrong encodingcheck value should trigger the UTF-8 trap redirect" );
    }

    @Test
    void checkUTF8_emptyValue_throwsRedirectException() {
        // Empty string is != sentinel, so the trap fires
        when( request.getParameter( "encodingcheck" ) ).thenReturn( "" );

        assertThrows( RedirectException.class,
                () -> signals.checkUTF8( context, change ),
                "An empty encodingcheck value should trigger the UTF-8 trap" );
    }

    @Test
    void checkUTF8_nullEncodingcheck_doesNotThrow() {
        // null → the outer null-check guard means the field is absent; no trap
        when( request.getParameter( "encodingcheck" ) ).thenReturn( null );

        assertDoesNotThrow( () -> signals.checkUTF8( context, change ),
                "A null encodingcheck (field absent) must not trigger the UTF-8 trap" );
    }

    @Test
    void checkUTF8_noHttpRequest_doesNotThrow() {
        when( context.getHttpRequest() ).thenReturn( null );

        assertDoesNotThrow( () -> signals.checkUTF8( context, change ),
                "With no HTTP request, the UTF-8 check must be silently skipped" );
    }

    @Test
    void checkUTF8_asciiLookalikeValue_throwsRedirectException() {
        // A different Japanese character is also wrong
        when( request.getParameter( "encodingcheck" ) ).thenReturn( "あ" );  // あ

        assertThrows( RedirectException.class,
                () -> signals.checkUTF8( context, change ),
                "A value that looks like UTF-8 but is not the exact sentinel should trigger the trap" );
    }

    // -----------------------------------------------------------------------
    // Score strategy (stopAtFirstMatch=false) — verifies score accumulates
    // -----------------------------------------------------------------------

    @Test
    void checkBotTrap_scoreStrategy_incrementsScoreInsteadOfThrowing() {
        final Properties props = new Properties();
        // No akismet key
        final DefaultSpamExternalSignals scorer =
                new DefaultSpamExternalSignals( props, false, "RejectedMessage" );

        final java.util.Map< String, Object > variables = new java.util.HashMap<>();
        doAnswer( inv -> {
            variables.put( inv.getArgument( 0 ), inv.getArgument( 1 ) );
            return null;
        } ).when( context ).setVariable( anyString(), any() );
        when( context.getVariable( anyString() ) ).thenAnswer( inv -> variables.get( inv.getArgument( 0 ) ) );

        when( request.getParameter( "submit_auth" ) ).thenReturn( "bot-value" );

        // Score strategy must NOT throw; instead it should increment a counter
        assertDoesNotThrow( () -> scorer.checkBotTrap( context, change ) );

        final Object score = variables.get( AbstractSpamStrategy.ATTR_SPAMFILTER_SCORE );
        assertNotNull( score, "Score variable should be set after a bot-trap hit" );
        assertEquals( 1, score, "Score should be 1 after one bot-trap hit" );
    }
}
