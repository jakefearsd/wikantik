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

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** Outbound HTTP for webhooks; implemented by the host, which owns the egress policy. */
public interface WorkOutbound {

    /** A destination the egress policy refused (at save, or at any hop of a delivery). */
    final class Refused extends IOException {
        private static final long serialVersionUID = 1L;

        public Refused( final String message ) {
            super( message );
        }
    }

    /** What a wiki with no outbound HTTP wired gets. */
    WorkOutbound NONE = new WorkOutbound() {
        @Override
        public void check( final URI url ) {
            throw new WorkValidationException( "url", "Webhooks are not available on this wiki." );
        }

        @Override
        public int post( final URI url, final String contentType, final String body,
                         final Map< String, String > headers ) throws IOException {
            throw new Refused( "Webhooks are not available on this wiki." );
        }
    };

    /** @throws WorkValidationException on field {@code url} with the reason when the destination is refused */
    void check( URI url );

    /** Posts the body and returns the HTTP status; every redirect hop is checked. */
    int post( URI url, String contentType, String body, Map< String, String > headers ) throws IOException;
}
