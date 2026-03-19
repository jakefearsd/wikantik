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
package com.wikantik.api.events;

import com.wikantik.api.core.Engine;
import com.wikantik.api.engine.Initializable;
import com.wikantik.event.WikiEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 *
 * @param  <T> the type of the events' source.
 */
public interface CustomWikiEventListener< T > extends WikiEventListener, Initializable {

    /** {@link WikiEventListener}s are stored on a {@code List< WeaKReference >} so we use this List to strong reference custom event listeners. */
    List< CustomWikiEventListener< ? > > LISTENERS = new ArrayList<>();

    /**
     * Returns the object of the events' source. Typically, it will be obtained on the
     * {@link Initializable#initialize(Engine, Properties)} method, and returned here.
     *
     * @return the object of the events' source.
     */
    T client();

}
