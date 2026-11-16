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
 * The store refused an agent's write because the item, read under lock, is claimed by another agent: the agent's copy
 * predated a claim that does not move the version (a claim on a review item). Nothing was written; the service turns
 * this into {@link WorkDeniedException}.
 */
final class ClaimFenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    static final String MESSAGE = "This item is claimed by another agent.";

    ClaimFenceException() {
        super( MESSAGE );
    }
}
