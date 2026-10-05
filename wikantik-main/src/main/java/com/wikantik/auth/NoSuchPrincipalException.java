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
package com.wikantik.auth;

/**
 *  Thrown in some error situations where a WikiPrincipal object does not exist.
 *  @since 2.3
 */
public final class NoSuchPrincipalException
    extends WikiSecurityException
{
    private static final long serialVersionUID = 3257849895976186169L;

    /** True when the lookup matched more than one principal rather than none. */
    private final boolean ambiguous;

    /**
     * Constructs a new exception object with a supplied message.
     * @param msg the message
     */
    public NoSuchPrincipalException(final String msg )
    {
        this( msg, false );
    }

    private NoSuchPrincipalException( final String msg, final boolean ambiguous )
    {
        super( msg );
        this.ambiguous = ambiguous;
    }

    /**
     * An exception for a lookup that matched more than one principal, so it identifies none.
     *
     * @param msg the message
     * @return the exception
     */
    public static NoSuchPrincipalException ambiguous( final String msg )
    {
        return new NoSuchPrincipalException( msg, true );
    }

    /**
     * Whether the lookup matched more than one principal (as opposed to none).
     *
     * @return {@code true} for an ambiguous lookup
     */
    public boolean isAmbiguous()
    {
        return ambiguous;
    }

    /**
     * Constructs a new exception object with a supplied message and cause.
     * @param msg the message
     * @param cause the underlying cause
     */
    public NoSuchPrincipalException(final String msg, final Throwable cause )
    {
        super( msg, cause );
        this.ambiguous = false;
    }
}
