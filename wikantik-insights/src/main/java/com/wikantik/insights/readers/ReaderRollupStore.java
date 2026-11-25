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
package com.wikantik.insights.readers;

import java.time.LocalDate;
import java.util.List;

/** Wikantik's own daily reader rollups, refreshed by the read-back. */
public interface ReaderRollupStore {

    /**
     * Atomically replaces every row of {@code siteHost} with a day in {@code [from, to]} by the
     * given rows. A failure leaves the previous rows untouched.
     */
    void replace( String siteHost, LocalDate from, LocalDate to, List< EngagementDay > engagement,
                  List< AcquisitionDay > acquisition );
}
