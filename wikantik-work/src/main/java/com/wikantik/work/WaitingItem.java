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

/** An item waiting on an agent, why, and the handoff event that explains it (nullable). */
public record WaitingItem( Item item, String reason, WorkEvent handoff ) {
    public static final String ASSIGNED = "assigned";
    public static final String MENTIONED = "mentioned";
    /** Closed by an agent with no person in its path: a lead acknowledges it. */
    public static final String ACKNOWLEDGE = "acknowledge";
    /** A merge the code host reported outside policy: a lead acknowledges it. */
    public static final String CHANGE_OUTSIDE_POLICY = "change_outside_policy";
}
