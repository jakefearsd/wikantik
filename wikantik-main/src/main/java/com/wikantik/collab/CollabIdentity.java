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

/** Port resolving a stored author key (login) to the public display name. */
public interface CollabIdentity {

    /**
     * The public display name of {@code authorKey}, or null when it has none (ruling R14); never the key itself. Every
     * caller tolerates null: presence falls back to the join's name or {@code "Editor <n>"}, the contributor list to
     * {@code "Unknown contributor"}, an outside writer to {@code agent}, and a byline omits {@code by}.
     */
    String displayName( String authorKey );
}
