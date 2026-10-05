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
package com.wikantik.auth.user;

import com.wikantik.auth.WikiSecurityException;

/**
 * A profile could not be saved because another account already holds its login name or wiki name
 * (a unique-constraint violation in the user store). The message is generic on purpose: callers
 * may show it to the client, and it never names the constraint or the other account.
 */
public class ProfileConflictException extends WikiSecurityException {

    private static final long serialVersionUID = 1L;

    /** The client-safe message every instance carries. */
    public static final String MESSAGE = "Another account already uses this login name or wiki name";

    /**
     * @param cause the underlying store failure, kept for server-side logs only
     */
    public ProfileConflictException( final Throwable cause ) {
        super( MESSAGE, cause );
    }
}
