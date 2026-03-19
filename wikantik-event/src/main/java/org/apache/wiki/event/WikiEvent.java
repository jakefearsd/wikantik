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

import java.util.EventObject;

/**
 * Abstract parent class for wiki events.
 * <p>
 * This class is sealed to restrict the event hierarchy to known subclasses,
 * improving type safety and enabling more efficient pattern matching.
 *
 * @since 2.3.79
 */
public abstract sealed class WikiEvent extends EventObject
        permits WikiEngineEvent, WikiPageEvent, WikiSecurityEvent {

    private static final long serialVersionUID = 1829433967558773960L;

    /** Indicates a exception or error state. */
    public static final int ERROR          = -99;

    /** Indicates an undefined state. */
    public static final int UNDEFINED      = -98;

    private int type = UNDEFINED;

    private final long when;

    /** objects associated to src which only make sense in the context of a given WikiEvent */
    private Object[] args;

    // ............

    /**
     * Constructs an instance of this event.
     *
     * @param src the Object that is the source of the event.
     * @param newType the event type.
     */
    public WikiEvent( final Object src, final int type ) {
        super( src );
        when = System.currentTimeMillis();
        args = new Object[]{};
        setType( type );
    }

    /**
     * Constructs an instance of this event.
     *
     * @param src the Object that is the source of the event. Typically, this is the Wiki {@link Engine}
     * @param newType the event type. Typically this is a constant reference to {@link WikiPageEvent}
     * @param args typically the first arg is the page name that triggered the event.
     */
    public WikiEvent( final Object src, final int type, final Object... args ) {
        this( src, type );
        this.args = args != null ? args : new Object[]{};
    }
    
    /**
     * Convenience method that returns the typed object to which the event applied.
     * 
     * @return the typed object to which the event applied.
     */
    @SuppressWarnings("unchecked")
    public < T > T getSrc() {
        return ( T )super.getSource();
    }

   /**
    *  Returns the timestamp of when this WikiEvent occurred.
    *
    * @return this event's timestamp
    * @since 2.4.74
    */
   public long getWhen() {
       return when;
   }

    /**
     * Sets the type of this event. Validation of acceptable type values is the responsibility of each subclass.
     *
     * @param newType the type of this WikiEvent.
     */
    protected void setType( final int newType ) {
        this.type = newType;
    }

    /**
     * Returns the type of this event.
     *
     * @return the type of this WikiEvent. See the enumerated values defined in {@link org.apache.wiki.event.WikiEvent}).
     */
    public int getType() {
        return type;
    }

    /**
     * Returns the args associated to src, if any.
     *
     * @return args associated to src, if any.
     */
    public Object[] getArgs() {
        return args;
    }

    /**
     * Returns the requested arg, if any.
     *
     * @return requested arg  or null.
     */
    public < T > T getArg(final int index, final Class< T > cls ) {
        if( index >= args.length ) {
            return null;
        }
        return ( T )args[ index ];
    }

    /**
     * Returns a String (human-readable) description of an event type. This should be subclassed as necessary.
     *
     * @return the String description
     */
    public String getTypeDescription() {
        return switch( type ) {
            case ERROR     -> "exception or error event";
            case UNDEFINED -> "undefined event type";
            default        -> "unknown event type (" + type + ")";
        };
    }

    /**
     * Returns true if the int value is a valid WikiEvent type. Because the WikiEvent class does not itself any event types,
     * this method returns true if the event type is anything except {@link #ERROR} or {@link #UNDEFINED}. This method is meant to
     * be subclassed as appropriate.
     * 
     * @param newType The value to test.
     * @return true, if the value is a valid WikiEvent type.
     */
    public static boolean isValidType( final int newType ) {
        return newType != ERROR && newType != UNDEFINED;
    }


    /**
     * Returns a textual representation of an event type.
     *
     * @return the String representation
     */
    public String eventName() {
        return switch( type ) {
            case ERROR     -> "ERROR";
            case UNDEFINED -> "UNDEFINED";
            default        -> "UNKNOWN (" + type + ")";
        };
    }

    /**
     * Prints a String (human-readable) representation of this object. This should be subclassed as necessary.
     *
     * @see java.lang.Object#toString()
     * @return the String representation
     */
    public String toString() {
        return "WikiEvent." + eventName() + " [source=" + getSource().toString() + "]";
    }

}
