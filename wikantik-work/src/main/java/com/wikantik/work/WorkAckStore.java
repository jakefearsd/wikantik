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

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/** Acknowledgement of items closed with no person in their path. Reached through {@link WorkStore#acks()}. */
public interface WorkAckStore {

    /** The item's acknowledgement state; {@link Ack#NONE} for an item that does not exist. */
    Ack state( long itemId ) throws SQLException;

    /**
     * Records that {@code by} acknowledged the item, with one {@code acknowledged} event, in one transaction.
     * False, and nothing written, when the item needs no acknowledgement or already has one.
     */
    boolean acknowledge( Item item, Actor by ) throws SQLException;

    /** Items of the given projects that wait for an acknowledgement, most recently closed first. */
    List< Item > awaiting( Set< Long > projectIds ) throws SQLException;
}
