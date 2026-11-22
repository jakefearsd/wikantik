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

/** A person's standing on one item's notifications. {@code NONE} is the absence of a row and is never stored. */
public enum WatchState implements WireEnum {
    WATCHING("watching"),
    MUTED("muted"),
    NONE("none");

    private final String wire;

    WatchState( final String wire ) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }

    /** Parses a wire value; throws {@link WorkValidationException} on field {@code state} when unknown. */
    public static WatchState fromWire( final String value ) {
        return WireEnum.parse( WatchState.class, value, "state" );
    }
}
