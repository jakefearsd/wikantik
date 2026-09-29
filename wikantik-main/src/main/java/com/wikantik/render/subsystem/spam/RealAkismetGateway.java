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
package com.wikantik.render.subsystem.spam;

import net.thauvin.erik.akismet.Akismet;
import net.thauvin.erik.akismet.AkismetComment;

/**
 * Production {@link AkismetGateway}: a thin wrapper around the real
 * {@code net.thauvin.erik.akismet.Akismet} client. Constructing one performs
 * no network I/O itself; {@link #verifyKey()} and {@link #checkComment} do.
 */
final class RealAkismetGateway implements AkismetGateway {

    private final Akismet akismet;

    RealAkismetGateway( final String apiKey, final String blog ) {
        this.akismet = new Akismet( apiKey, blog );
    }

    @Override
    public boolean verifyKey() {
        return akismet.verifyKey();
    }

    @Override
    public boolean checkComment( final AkismetComment comment ) {
        return akismet.checkComment( comment );
    }
}
