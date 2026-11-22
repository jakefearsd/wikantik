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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * The flow figures of one project over a window, computed at read time from the event log: items finished per
 * week, how long they took, and what is in progress now.
 */
public record FlowReport( LocalDate from, LocalDate to, String zone, List< Week > throughput, Durations cycleTime,
                          Durations leadTime, List< Wip > wip ) {

    /** The items (and story points) whose last close fell in the week starting {@code weekStart}. */
    public record Week( LocalDate weekStart, int items, BigDecimal points ) {
    }

    /** The items in one status now: the total and the oldest few. */
    public record Wip( WorkStatus status, int count, List< WipItem > items ) {
    }

    /** One item in progress and how long it has been. */
    public record WipItem( String key, String title, Duration age ) {
    }
}
