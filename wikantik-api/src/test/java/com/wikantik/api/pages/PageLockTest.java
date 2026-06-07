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
package com.wikantik.api.pages;

import com.wikantik.api.core.Page;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PageLockTest {

    private static Page pageNamed( final String name ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        return p;
    }

    private static final long ONE_MINUTE_MS = 60_000L;

    @Test
    void exposesPageNameAndLocker() {
        final PageLock lock = new PageLock( pageNamed( "MyPage" ), "bob",
            new Date( 1_000L ), new Date( 2_000L ) );

        assertEquals( "MyPage", lock.getPage() );
        assertEquals( "bob", lock.getLocker() );
        assertEquals( new Date( 1_000L ), lock.getAcquisitionTime() );
        assertEquals( new Date( 2_000L ), lock.getExpiryTime() );
    }

    @Test
    void doesNotRetainAReferenceToCallerSuppliedDates() {
        final Date acquired = new Date( 1_000L );
        final Date expiry = new Date( 2_000L );
        final PageLock lock = new PageLock( pageNamed( "P" ), "bob", acquired, expiry );

        acquired.setTime( 9_999L );   // mutate caller's instances after construction
        expiry.setTime( 8_888L );

        assertEquals( 1_000L, lock.getAcquisitionTime().getTime() );
        assertEquals( 2_000L, lock.getExpiryTime().getTime() );
    }

    @Test
    void accessorsReturnDefensiveCopies() {
        final PageLock lock = new PageLock( pageNamed( "P" ), "bob",
            new Date( 1_000L ), new Date( 2_000L ) );

        lock.getAcquisitionTime().setTime( 0L );   // mutating the returned copy must not leak back
        lock.getExpiryTime().setTime( 0L );

        assertEquals( 1_000L, lock.getAcquisitionTime().getTime() );
        assertEquals( 2_000L, lock.getExpiryTime().getTime() );
    }

    @Test
    void futureExpiryIsNotExpiredAndReportsPositiveMinutes() {
        final Date expiry = new Date( System.currentTimeMillis() + 10 * ONE_MINUTE_MS );
        final PageLock lock = new PageLock( pageNamed( "P" ), "bob", new Date(), expiry );

        assertFalse( lock.isExpired() );
        // (remaining_ms / 60000) + 1, with a touch of elapsed time → 10 or 11.
        final long left = lock.getTimeLeft();
        assertTrue( left >= 9 && left <= 11, "expected ~10 minutes left, was " + left );
    }

    @Test
    void pastExpiryIsExpiredAndReportsNonPositiveMinutes() {
        final Date expiry = new Date( System.currentTimeMillis() - 5 * ONE_MINUTE_MS );
        final PageLock lock = new PageLock( pageNamed( "P" ), "bob", new Date(), expiry );

        assertTrue( lock.isExpired() );
        assertTrue( lock.getTimeLeft() <= 0, "expired lock should report no minutes left" );
    }
}
