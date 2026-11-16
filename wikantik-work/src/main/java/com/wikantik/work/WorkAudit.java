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
 * The port through which registry changes (queues and agents) reach the wiki's admin audit trail, implemented in the
 * wiki and a no-op by default. An entry names the acting admin, the action and the agent or queue: never a token or
 * an API key id.
 */
@FunctionalInterface
public interface WorkAudit {

    /**
     * Records one registry change. Never throws: a failing audit backend must not undo a change that already happened.
     *
     * @param actorLogin the acting admin's login
     * @param action     the event type, e.g. {@code work.agent.register}
     * @param targetType {@code work_agent} or {@code work_queue}
     * @param target     the agent's name or the queue's key
     * @param detail     a short sentence, or null
     */
    void record( String actorLogin, String action, String targetType, String target, String detail );

    /** Records nothing. */
    WorkAudit NONE = ( actorLogin, action, targetType, target, detail ) -> { };
}
