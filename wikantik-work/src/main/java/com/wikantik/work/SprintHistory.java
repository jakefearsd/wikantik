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

import java.util.List;

/**
 * The events of every item ever in a sprint, with the ids of the sprint's own start and close events: a change
 * counts as before the start when its id is lower (event ids are taken under the sprint lock; timestamps are not).
 * {@code startedEventId} is 0 when the sprint has not started; {@code closedEventId} is {@code Long.MAX_VALUE} when
 * it has not closed.
 */
public record SprintHistory( List< SprintEvent > events, long startedEventId, long closedEventId ) {
}
