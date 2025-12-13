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
package org.apache.wiki.pages;

import org.apache.wiki.api.core.Page;

import java.io.Serializable;
import java.util.Date;


/**
 * Describes a lock acquired by an user on a page.  For the most part, the regular developer does not have to instantiate this class.
 * <p>
 * The PageLock keeps no reference to a WikiPage because otherwise it could keep a reference to a page for a long time.
 */
public class PageLock implements Serializable {

    private static final long serialVersionUID = 0L;

    private final String page;
    private final String locker;
    private final Date lockAcquired;
    private final Date lockExpiry;

    /**
     * Creates a new PageLock.  The lock is not attached to any objects at this point.
     *
     * @param newPage     WikiPage which is locked.
     * @param locker   The username who locked this page (for display purposes).
     * @param acquired The timestamp when the lock is acquired
     * @param expiry   The timestamp when the lock expires.
     */
    public PageLock( final Page newPage, final String lockerName, final Date acquired, final Date expiry ) {
        this.page = newPage.getName();
        this.locker = lockerName;
        this.lockAcquired = ( Date )acquired.clone();
        this.lockExpiry = ( Date )expiry.clone();
    }

    /**
     * Returns the name of the page which is locked.
     *
     * @return The name of the page.
     */
    public String getPage() {
        return page;
    }

    /**
     * Returns the locker name.
     *
     * @return The name of the locker.
     */
    public String getLocker() {
        return locker;
    }

    /**
     * Returns the timestamp on which this lock was acquired.
     *
     * @return The acquisition time.
     */
    public Date getAcquisitionTime() {
        return lockAcquired;
    }

    /**
     * Returns the timestamp on which this lock will expire.
     *
     * @return The expiry date.
     */
    public Date getExpiryTime() {
        return lockExpiry;
    }

    /**
     * Returns the amount of time left in minutes, rounded up to the nearest minute (so you get a zero only at the last minute).
     *
     * @return Time left in minutes.
     */
    public long getTimeLeft() {
        final long time = lockExpiry.getTime() - new Date().getTime();
        return ( time / ( 1000L * 60 ) ) + 1;
    }

    public boolean isExpired() {
        final Date now = new Date();
        return now.after( getExpiryTime() );
    }

}
