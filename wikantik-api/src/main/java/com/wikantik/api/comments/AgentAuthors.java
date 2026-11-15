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
package com.wikantik.api.comments;

/** Author-name convention for comments posted through an agent credential: {@code agent:<clientName>}. */
public final class AgentAuthors {

    public static final String PREFIX = "agent:";

    private static final String UNKNOWN = "unknown";

    private AgentAuthors() {
    }

    /** The stored author for an agent client; blank or null names become {@code agent:unknown}. */
    public static String of( final String clientName ) {
        final String name = clientName == null || clientName.isBlank() ? UNKNOWN : clientName.trim();
        return PREFIX + name;
    }

    /** The author with the {@link #PREFIX} stripped, for display. */
    public static String displayName( final String author ) {
        return author != null && author.startsWith( PREFIX ) ? author.substring( PREFIX.length() ) : author;
    }
}
