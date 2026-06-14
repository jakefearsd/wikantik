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
package com.wikantik.derived;

/** Result returned by {@link DerivedPageIngestionService#ingest}. */
public record IngestResult( Status status, String pageName, String message ) {

    public enum Status { CREATED, UPDATED, UNCHANGED, FAILED }

    public static IngestResult created( final String pageName ) {
        return new IngestResult( Status.CREATED, pageName, "created" );
    }

    public static IngestResult updated( final String pageName ) {
        return new IngestResult( Status.UPDATED, pageName, "updated" );
    }

    public static IngestResult unchanged( final String pageName ) {
        return new IngestResult( Status.UNCHANGED, pageName, "unchanged — same SHA" );
    }

    public static IngestResult failed( final String pageName, final String reason ) {
        return new IngestResult( Status.FAILED, pageName, reason );
    }
}
