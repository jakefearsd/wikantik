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

/** How much a mailed notification says: {@code summary} names the item and what happened; {@code full} adds comment text. */
public enum EmailDetail implements WireEnum {
    SUMMARY("summary"),
    FULL("full");

    private final String detail;

    EmailDetail( final String detail ) {
        this.detail = detail;
    }

    @Override
    public String wire() {
        return detail;
    }

    /** Parses a configured value; throws {@link WorkValidationException} on field {@code detail} when unknown. */
    public static EmailDetail fromWire( final String value ) {
        return WireEnum.parse( EmailDetail.class, value, "detail" );
    }
}
