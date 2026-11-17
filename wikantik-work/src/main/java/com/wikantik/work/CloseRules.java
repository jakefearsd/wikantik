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
 * What a move to {@code done} must satisfy or record, decided by the service from the item's effective policy
 * and enforced by the store in the item write. {@code ackRequired}: the close leaves no person in the item's
 * path, so it is flagged until one acknowledges it. {@code needsDeployment}: the item's epic counts work as done
 * only in production, so the close is refused unless a verified deployment carries the item.
 */
public record CloseRules( boolean ackRequired, boolean needsDeployment ) {

    public static final CloseRules NONE = new CloseRules( false, false );

    boolean any() {
        return ackRequired || needsDeployment;
    }
}
