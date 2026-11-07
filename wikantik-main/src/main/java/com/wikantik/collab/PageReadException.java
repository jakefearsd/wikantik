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
package com.wikantik.collab;

/**
 * The page store could not tell what a page holds (a provider error, an unreadable text): the answer is unknown. It is
 * never reported as "missing" (that would freeze a draft or drop an owed rename) nor as an empty body (a settle would
 * merge it as an outside write emptying the draft). Callers treat it as "try again later".
 */
public final class PageReadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PageReadException( final String message ) {
        super( message );
    }

    public PageReadException( final String message, final Throwable cause ) {
        super( message, cause );
    }
}
