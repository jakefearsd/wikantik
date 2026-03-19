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
package org.apache.wiki.event;

/**
  * WikiEngineEvent indicates a change in the state of the Engine.
  *
  * @see     org.apache.wiki.event.WikiEvent
  * @since   2.4.20
  */
public final class WikiEngineEvent extends WikiEvent {

    private static final long serialVersionUID = 1829433967558773970L;

    /** Indicates a Engine initialization event, fired as the  wiki service is being initialized (in progress). */
    public static final int INITIALIZING   = -1;

    /** Indicates a Engine initialized event, fired after the  wiki service is fully available. */
    public static final int INITIALIZED    = 0;

    /** Indicates a Engine closing event, fired as a signal that the wiki service is shutting down. */
    public static final int SHUTDOWN       = 1;

    /**
     * Indicates a Engine stopped event, fired after halting the wiki service. A Engine in this state is not expected to provide further
     * services.
     */
    public static final int STOPPED        = 2;

     /**
      *  Constructs an instance of this event.
      *
      * @param eventSource the Object that is the source of the event, which <b>must</b> be the Engine. If it is not, this method thows
      *                    a ClassCastException
      * @param type the event type
      */
    public WikiEngineEvent( final Object eventSource, final int type ) {
        super( eventSource, type );
    }

    /**
     *  Sets the type of this event.
     *
     * @param type the type of this WikiEngineEvent.
     */
    @Override
    protected void setType( final int type ) {
        if( type >= INITIALIZING && type <= STOPPED ) {
            super.setType( type );
        } else {
            super.setType( ERROR );
        }
    }

    /**
     * Returns <code>true</code> if the int value is a WikiPageEvent type.
     *
     * @param type the event type
     * @return the result
     */
    public static boolean isValidType( final int type ) {
        return type >= INITIALIZING && type <= STOPPED;
    }

    /**
     *  Returns a textual representation of the event type.
     *
     * @return a String representation of the type
     */
    @Override
    public final String eventName() {
        return switch ( getType() ) {
            case INITIALIZING -> "INITIALIZING";
            case INITIALIZED  -> "INITIALIZED";
            case SHUTDOWN     -> "SHUTDOWN";
            case STOPPED      -> "STOPPED";
            default           -> super.eventName();
        };
    }

    /**
     *  Returns a human-readable description of the event type.
     *
     * @return a String description of the type
     */
    @Override
    public final String getTypeDescription() {
        return switch ( getType() ) {
            case INITIALIZING -> "wiki engine initializing";
            case INITIALIZED  -> "wiki engine initialized";
            case SHUTDOWN     -> "wiki engine shutting down";
            case STOPPED      -> "wiki engine stopped";
            default           -> super.getTypeDescription();
        };
    }

}
