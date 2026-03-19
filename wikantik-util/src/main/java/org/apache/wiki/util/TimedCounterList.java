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
package org.apache.wiki.util;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 *  Provides a List in which all items store their addition time. This
 *  can then be used to clean the list from old items.
 *  <p>
 *  This class is thread-safe - all modifications are blocking, but
 *  reading is non-blocking (unless a write is ongoing).
 *  
 *  @param <T> The class you wish to store here
 *  @since 2.8
 */
public class TimedCounterList<T> extends AbstractList<T>
{
    private final ArrayList<CounterItem<T>> list = new ArrayList<>();
    private final ReadWriteLock             lock = new ReentrantReadWriteLock();
    
    /**
     *  {@inheritDoc}
     */
    @Override
    public T set(final int index, final T element )
    {
        lock.writeLock().lock();
        
        T t;
        
        try
        {
            t = list.set(index, new CounterItem<>(element)).obj;
        }
        finally
        {
            lock.writeLock().unlock();
        }
        
        return t;
    }
    
    /**
     *  {@inheritDoc}
     */
    @Override
    public T get(final int index )
    {
        lock.readLock().lock();
        
        T t;
        
        try
        {
            t = list.get(index).obj;
        }
        finally
        {
            lock.readLock().unlock();
        }
        
        return t;
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public int size()
    {
        lock.readLock().lock();
        int size;

        try
        {
            size = list.size();
        }
        finally
        {
            lock.readLock().unlock();
        }
        
        return size;
    }
    
    /**
     *  {@inheritDoc}
     */
    @Override
    public void add(final int index, final T element )
    {
        lock.writeLock().lock();
        
        try
        {
            list.add(index, new CounterItem<>(element));
        }
        finally
        {
            lock.writeLock().unlock();
        }
    }
    
    /**
     *  {@inheritDoc}
     */
    @Override
    public T remove(final int index )
    {
        lock.writeLock().lock();
        T t;

        try
        {
            t = list.remove( index ).obj;
        }
        finally
        {
            lock.writeLock().unlock();
        }
        
        return t;
    }

    /**
     *  Returns the count how many times this object is available in
     *  this list, using equals().
     *  
     *  @param newObj The object to count.
     *  @return The count of the objects.
     */
    public int count(final T newObj )
    {
        int c;
        lock.readLock().lock();
        
        try
        {
            c = (int) list.stream().filter(i -> i.obj.equals(newObj)).count();
        }
        finally
        {
            lock.readLock().unlock();
        }
        
        return c;
    }
    
    /**
     *  Performs a cleanup of all items older than maxage.
     *  
     *  @param maxage The maximum age in milliseconds after an item is removed.
     */
    public void cleanup(final long maxage )
    {
        lock.writeLock().lock();
        
        try
        {
            final long now = System.currentTimeMillis();
        
            for(final Iterator<CounterItem<T>> i = list.iterator(); i.hasNext(); )
            {
                final CounterItem<T> ci = i.next();
            
                final long age = now - ci.addTime;
            
                if( age > maxage )
                {
                    i.remove();
                }
            }
        }
        finally
        {
            lock.writeLock().unlock();
        }
    }
    
    /**
     *  Returns the time when this particular item was added on the list.
     *  
     *  @param index The index of the object.
     *  @return The addition time in milliseconds (@see System.currentTimeMillis()).
     */
    public long getAddTime(final int index )
    {
        lock.readLock().lock();
        long res;
        
        try
        {
            res = list.get( index ).addTime;
        }
        finally
        {
            lock.readLock().unlock();
        }
        
        return res;
    }
    
    private static class CounterItem<E>
    {
        private final E      obj;
        private final long   addTime;
        
        public CounterItem(final E o)
        {
            addTime = System.currentTimeMillis();
            obj = o;
        }
    }


}
