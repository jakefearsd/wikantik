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
package com.wikantik.work;

/**
 * The shape of an inbound delivery's signature header, checked before the body is read. Kept off
 * {@link WorkCodeHostService} because it needs no product, hook or caller.
 */
public final class HookSignature {

    private HookSignature() {
    }

    /**
     * Whether a signature header is worth reading a body for: {@code sha256=} and 64 hex digits. A header that is not
     * can never verify, so the hook refuses it before reading the body. Says nothing about any product or hook.
     */
    public static boolean wellFormed( final String header ) {
        return CodeHostSecrets.shaped( header );
    }
}
