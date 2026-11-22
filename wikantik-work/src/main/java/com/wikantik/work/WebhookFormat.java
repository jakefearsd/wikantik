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

/** The body a webhook receives: the wiki's own JSON, or a Slack incoming-webhook message. */
public enum WebhookFormat implements WireEnum {
    GENERIC("generic"),
    SLACK("slack");

    private final String wire;

    WebhookFormat( final String wire ) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }

    /** Parses a stored or submitted value; throws {@link WorkValidationException} on field {@code format}. */
    public static WebhookFormat fromWire( final String value ) {
        return WireEnum.parse( WebhookFormat.class, value, "format" );
    }
}
