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
package com.wikantik.api.exceptions;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;


/**
 *  Provides a generic PluginException.  This is the kind of an exception that the plugins should throw.
 */
public class PluginException  extends WikiException {

    private static final long serialVersionUID = -289900047240960332L;

    private final Throwable throwable;

    /**
     *  Create a PluginException.
     *
     *  @param message exception message.
     */
    public PluginException( final String message ) {
        super( message );
        throwable = null;
    }

    /**
     *  Create a PluginException with the given original exception wrapped.
     *
     *  @param message exception message.
     *  @param original The original exception.
     */
    @SuppressFBWarnings( value = "EI_EXPOSE_REP2",
            justification = "Wrapping the caller's Throwable is the explicit purpose of this exception; defensive-copying a Throwable is impractical and would discard state." )
    public PluginException( final String message, final Throwable original ) {
        super( message, original );
        throwable = original;
    }

    /**
     *  Return the original exception.
     *
     *  @return The original exception.
     */
    @SuppressFBWarnings( value = "EI_EXPOSE_REP",
            justification = "Exposing the wrapped Throwable is the contract of this accessor." )
    public Throwable getRootThrowable() {
        return throwable;
    }

}
