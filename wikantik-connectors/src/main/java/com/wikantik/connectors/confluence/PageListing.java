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
package com.wikantik.connectors.confluence;

import java.util.List;

/** Space-pages listing result. {@code skippedMalformed} counts listed pages missing required fields
 *  (body.storage/version) — a listing anomaly, NOT authoritative absence: the connector must taint
 *  the batch (complete=false) so the orchestrator derives no tombstones from their absence. */
public record PageListing( List< ConfluencePage > pages, int skippedMalformed ) {}
