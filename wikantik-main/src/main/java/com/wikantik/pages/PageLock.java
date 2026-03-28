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
package com.wikantik.pages;

import com.wikantik.api.core.Page;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.Date;


/**
 * Backward-compatibility shim.
 *
 * @deprecated Use {@link com.wikantik.api.pages.PageLock} instead.
 */
@Deprecated( since = "2.12.0", forRemoval = true )
@SuppressFBWarnings( value = "NM_SAME_SIMPLE_NAME_AS_SUPERCLASS",
        justification = "Intentional: backward-compatibility shim, scheduled for removal" )
public class PageLock extends com.wikantik.api.pages.PageLock {

    private static final long serialVersionUID = 0L;

    /**
     * Creates a new PageLock.  The lock is not attached to any objects at this point.
     *
     * @param newPage  WikiPage which is locked.
     * @param lockerName The username who locked this page (for display purposes).
     * @param acquired The timestamp when the lock is acquired
     * @param expiry   The timestamp when the lock expires.
     */
    public PageLock( final Page newPage, final String lockerName, final Date acquired, final Date expiry ) {
        super( newPage, lockerName, acquired, expiry );
    }

}
