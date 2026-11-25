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

/**
 * Read-only access to the four views the back-office suite exposes over Umami's database. Every
 * query is scoped to one website and a closed day range {@code [from, to]}.
 */
public interface UmamiViews {

    /** Daily sessions and views per URL path. */
    record TrafficRow( LocalDate day, String path, long sessions, long views ) { }

    /** Daily engagement events per page, already keyed by canonical id ({@code ''}/null = site). */
    record EngagementRow( LocalDate day, String canonicalId, long exits, Double activeMedianS,
                          Double activeP90S, long exitsScroll75, long codeCopies,
                          long wikilinkClicks, long outboundClicks, long diagramInteracts,
                          long tocClicks, long signInClicks ) { }

    /** Daily 75th-percentile web vitals per URL path; null percentile = no data. */
    record VitalsRow( LocalDate day, String path, long samples, Double lcpP75, Double inpP75,
                      Double clsP75 ) { }

    /** Daily landings per URL path, channel and source. */
    record AcquisitionRow( LocalDate day, String path, String channel, String source,
                           long landings, long continued, long returningLandings ) { }

    List< TrafficRow > traffic( LocalDate from, LocalDate to );

    List< EngagementRow > engagement( LocalDate from, LocalDate to );

    List< VitalsRow > vitals( LocalDate from, LocalDate to );

    List< AcquisitionRow > acquisition( LocalDate from, LocalDate to );
}
