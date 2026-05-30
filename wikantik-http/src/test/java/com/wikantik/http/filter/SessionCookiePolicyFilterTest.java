/*
 * Copyright 2025 The Apache Software Foundation.
 *
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
 */
package com.wikantik.http.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionCookiePolicyFilterTest {

    @Test
    void classify_flags_strict_session_cookie() {
        assertEquals( SessionCookiePolicyFilter.Policy.STRICT,
                SessionCookiePolicyFilter.classify( "JSESSIONID=abc; Path=/; HttpOnly; SameSite=Strict" ) );
        // Case-insensitive on both the name and the SameSite value.
        assertEquals( SessionCookiePolicyFilter.Policy.STRICT,
                SessionCookiePolicyFilter.classify( "jsessionid=abc; samesite=strict" ) );
    }

    @Test
    void classify_flags_missing_samesite_session_cookie() {
        assertEquals( SessionCookiePolicyFilter.Policy.MISSING,
                SessionCookiePolicyFilter.classify( "JSESSIONID=abc; Path=/; HttpOnly" ) );
    }

    @Test
    void classify_accepts_lax_session_cookie() {
        assertEquals( SessionCookiePolicyFilter.Policy.OK,
                SessionCookiePolicyFilter.classify( "JSESSIONID=abc; Path=/; HttpOnly; SameSite=Lax" ) );
    }

    @Test
    void classify_flags_strict_remember_me_cookie() {
        // The remember-me cookie (and its legacy name) carry the same risk as the
        // session cookie and must also be Lax.
        assertEquals( SessionCookiePolicyFilter.Policy.STRICT,
                SessionCookiePolicyFilter.classify( "WikantikUID=zzz; HttpOnly; SameSite=Strict" ) );
        assertEquals( SessionCookiePolicyFilter.Policy.STRICT,
                SessionCookiePolicyFilter.classify( "JSPWikiUID=zzz; SameSite=Strict" ) );
        assertEquals( SessionCookiePolicyFilter.Policy.OK,
                SessionCookiePolicyFilter.classify( "WikantikUID=zzz; HttpOnly; SameSite=Lax" ) );
    }

    @Test
    void classify_ignores_non_auth_cookies() {
        assertEquals( SessionCookiePolicyFilter.Policy.NOT_SESSION,
                SessionCookiePolicyFilter.classify( "WikantikAssertedName=bob; SameSite=Strict" ) );
        assertEquals( SessionCookiePolicyFilter.Policy.NOT_SESSION,
                SessionCookiePolicyFilter.classify( "theme=dark; SameSite=Strict" ) );
        assertEquals( SessionCookiePolicyFilter.Policy.NOT_SESSION,
                SessionCookiePolicyFilter.classify( null ) );
    }

    @Test
    void filter_is_transparent_and_continues_chain() throws Exception {
        final SessionCookiePolicyFilter filter = new SessionCookiePolicyFilter();
        final HttpServletResponse res = Mockito.mock( HttpServletResponse.class );
        Mockito.when( res.isCommitted() ).thenReturn( false );
        Mockito.when( res.getHeaders( "Set-Cookie" ) )
                .thenReturn( List.of( "JSESSIONID=abc; Path=/; HttpOnly; SameSite=Strict" ) );
        final FilterChain chain = Mockito.mock( FilterChain.class );

        filter.doFilter( Mockito.mock( jakarta.servlet.ServletRequest.class ), res, chain );

        // The filter never blocks: the chain must always proceed, and the response
        // is only inspected, never mutated.
        Mockito.verify( chain ).doFilter( Mockito.any(), Mockito.any() );
        Mockito.verify( res, Mockito.never() ).addHeader( Mockito.anyString(), Mockito.anyString() );
        Mockito.verify( res, Mockito.never() ).setHeader( Mockito.anyString(), Mockito.anyString() );
    }
}
