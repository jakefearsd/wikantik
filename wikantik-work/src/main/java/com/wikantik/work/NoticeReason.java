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

/** Why a person is told about an event. Declaration order is strength, strongest first. */
public enum NoticeReason implements WireEnum {
    MENTIONED("mentioned"),
    ASSIGNED("assigned"),
    /** A person who only reports filed a request, or commented on one nobody holds: told to the leads. */
    REQUESTED("requested"),
    ACKNOWLEDGE("acknowledge"),
    CLAIM_EXPIRED("claim_expired"),
    WEBHOOK_DISABLED("webhook_disabled"),
    WATCHING("watching");

    private final String wire;

    NoticeReason( final String wire ) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }

    /** Parses a stored value; throws {@link WorkValidationException} on field {@code reason} when unknown. */
    public static NoticeReason fromWire( final String value ) {
        return WireEnum.parse( NoticeReason.class, value, "reason" );
    }
}
