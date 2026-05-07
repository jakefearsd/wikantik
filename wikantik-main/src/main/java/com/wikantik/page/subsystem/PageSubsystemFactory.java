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
package com.wikantik.page.subsystem;

import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.cache.CachingManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.content.PageRenamer;
import com.wikantik.page.subsystem.lifecycle.PageLifecycle;
import com.wikantik.page.subsystem.lifecycle.PageLockService;
import com.wikantik.page.subsystem.lifecycle.PageRepository;
import com.wikantik.pages.DefaultPageManager;
import com.wikantik.util.ClassUtil;
import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Properties;

/**
 * Builds {@link PageSubsystem.Services} from {@link PageSubsystem.Deps}.
 *
 * <p>Phase 5 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}.</p>
 *
 * <p>Phase 5 Checkpoint 1 locates the four manager-level objects on the
 * engine's legacy registry, mirroring the Auth pattern. Construction of
 * {@code DefaultPageManager} stays in {@code WikiEngine.initComponent}
 * for now; Ckpt 2 lifts the page-provider chain construction here, and
 * Ckpt 3 lifts the manager construction itself.</p>
 */
public final class PageSubsystemFactory {

    private static final Logger LOG = LogManager.getLogger( PageSubsystemFactory.class );

    /** Property name for the configured page provider implementation. Mirrors
     *  {@code com.wikantik.pages.DefaultPageManager.PROP_PAGEPROVIDER} so the
     *  factory can build the chain without the manager being instantiated. */
    private static final String PROP_PAGEPROVIDER = "wikantik.pageProvider";

    private PageSubsystemFactory() {}

    /**
     * Builds the {@link PageProvider} chain — caching wrapper (if enabled)
     * around the configured base provider (typically
     * {@code VersioningFileProvider}). Lifted from {@code DefaultPageManager}'s
     * constructor in Phase 5 Checkpoint 2 so the chain is owned by the
     * subsystem boundary rather than the manager impl.
     *
     * <p>Behaviour matches the original construction: read
     * {@code CachingManager.CACHE_PAGES} to decide whether to wrap with
     * {@code CachingProvider}; otherwise instantiate the class named by
     * {@code wikantik.pageProvider}. The returned provider has been
     * {@code initialize(engine, props)}-ed and is ready to use.</p>
     */
    public static PageProvider buildProvider( final Engine engine, final Properties props ) throws WikiException {
        Objects.requireNonNull( engine, "engine" );
        Objects.requireNonNull( props, "props" );

        final com.wikantik.cache.CachingManager cm = com.wikantik.core.subsystem.CoreSubsystemBridge.fromLegacyEngine( engine ).cachingManager();
        final boolean useCache = cm != null ? cm.enabled( com.wikantik.cache.CachingManager.CACHE_PAGES ) : false;
        final String classname = useCache
            ? "com.wikantik.providers.CachingProvider"
            : TextUtil.getRequiredProperty( props, PROP_PAGEPROVIDER );

        try {
            LOG.debug( "Page provider class: '{}'", classname );
            final PageProvider provider = ClassUtil.buildInstance( "com.wikantik.providers", classname );
            LOG.debug( "Initializing page provider class {}", provider );
            provider.initialize( engine, props );
            return provider;
        } catch ( final ReflectiveOperationException e ) {
            // LOG.error justified: misconfigured wikantik.pageProvider class blocks engine startup; operators need a stack trace to diagnose.
            LOG.error( "Unable to instantiate provider class '{}' ({})", classname, e.getMessage(), e );
            throw new WikiException( "Illegal provider class. (" + e.getMessage() + ")", e );
        } catch ( final NoRequiredPropertyException e ) {
            // LOG.error justified: required wiki property missing means engine cannot boot.
            LOG.error( "Provider did not find a property it was looking for: {}", e.getMessage(), e );
            throw e;
        } catch ( final IOException e ) {
            // LOG.error justified: provider IO failure during boot is a fatal startup condition.
            LOG.error( "An I/O exception occurred while creating page provider {}: {}", classname, e.getMessage(), e );
            throw new WikiException( "Unable to start page provider: " + e.getMessage(), e );
        } catch ( final NoSuchElementException e ) {
            throw e;
        }
    }

    public static PageSubsystem.Services create( final PageSubsystem.Deps deps ) {
        Objects.requireNonNull( deps, "deps" );
        Objects.requireNonNull( deps.core(), "core" );
        final Engine engine = Objects.requireNonNull( deps.engine(), "engine" );

        final PageManager       pages       = engine.getManager( PageManager.class );
        final AttachmentManager attachments = engine.getManager( AttachmentManager.class );
        final PageRenamer       renamer     = engine.getManager( PageRenamer.class );
        final PageSaveHelper    saveHelper  = new PageSaveHelper( engine );
        final PageProvider      provider    = pages != null ? pages.getProvider() : null;
        // ReferenceManager is initialized asynchronously after the page-manager scan
        // completes; it may be null at this point and will be populated later.
        final ReferenceManager  refMgr      = engine.getManager( ReferenceManager.class );

        PageRepository  pageRepository  = null;
        PageLifecycle   pageLifecycle   = null;
        PageLockService pageLockService = null;
        if ( pages instanceof DefaultPageManager dpm ) {
            pageRepository  = dpm.getRepository();
            pageLifecycle   = dpm.getLifecycle();
            pageLockService = dpm.getLockService();
        }

        return new PageSubsystem.Services( pages, attachments, renamer, saveHelper, provider,
                                           pageRepository, pageLifecycle, pageLockService, refMgr );
    }
}
