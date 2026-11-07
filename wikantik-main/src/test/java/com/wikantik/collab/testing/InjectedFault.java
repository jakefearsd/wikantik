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
package com.wikantik.collab.testing;

/**
 * The failure a fault point of {@link InMemoryCollabStore} injects. An {@link IllegalStateException} so it travels the
 * same path as a real storage fault, yet a distinct type so a test can tell an injected failure from a genuine
 * contract refusal.
 */
public final class InjectedFault extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public InjectedFault( final String message ) {
        super( message );
    }
}
