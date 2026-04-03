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
package com.wikantik.blog;

import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.engine.Initializable;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;

import java.util.List;

/**
 * Manager for user blog lifecycle: creation, entry management, listing, and deletion.
 *
 * <p>Blogs are stored as real subdirectories under {@code pageDir/blog/<username>/}.
 * Blog entries are full wiki pages with YYYYMMDD-prefixed filenames. Each user may
 * have at most one blog (v1).
 *
 * @since 3.0.8
 */
public interface BlogManager extends Initializable {

    /** Subdirectory under pageDir where all blogs live. */
    String BLOG_DIR = "blog";

    /** Name of the blog homepage file (without extension). */
    String BLOG_HOME_PAGE = "Blog";

    /**
     * Creates a new blog for the authenticated user.
     *
     * <p>Derives the username from the session, lowercases it, creates the blog
     * subdirectory under {@code pageDir/blog/<username>/}, and seeds an initial
     * {@code Blog.md} homepage with default frontmatter.
     *
     * @param session the current user session; must be authenticated
     * @return the newly created {@code Blog.md} page
     * @throws BlogAlreadyExistsException if a blog directory already exists for this user
     * @throws WikiException              if the page cannot be created
     */
    Page createBlog( Session session ) throws WikiException;

    /**
     * Deletes an existing blog and all its entries.
     *
     * <p>Validates that the session user either owns the blog (username matches) or
     * holds the {@code Admin} role. Removes all entry pages and the blog directory.
     *
     * @param session  the current user session; must own the blog or be an Admin
     * @param username the login name (lowercase) of the blog owner to delete
     * @throws WikiException if the caller lacks permission or deletion fails
     */
    void deleteBlog( Session session, String username ) throws WikiException;

    /**
     * Creates a new blog entry for the authenticated user.
     *
     * <p>Validates that the session user owns the blog. The entry page name is formed
     * by prepending the current date as {@code YYYYMMDD} to the given topic name.
     *
     * @param session   the current user session; must own the blog
     * @param topicName the entry topic (will be prefixed with the current date)
     * @return the newly created entry page
     * @throws WikiException if the caller does not own a blog or creation fails
     */
    Page createEntry( Session session, String topicName ) throws WikiException;

    /**
     * Returns the {@code Blog.md} homepage page for the given user, or {@code null}
     * if the user has no blog.
     *
     * @param username the login name (lowercase) of the blog owner
     * @return the {@code Blog.md} page, or {@code null} if no blog exists
     * @throws ProviderException if the underlying page provider encounters an error
     */
    Page getBlog( String username ) throws ProviderException;

    /**
     * Returns all entry pages for the given user's blog, excluding {@code Blog.md},
     * sorted by entry date descending (most recent first).
     *
     * @param username the login name (lowercase) of the blog owner
     * @return list of entry pages, newest first; empty list if no entries exist
     * @throws ProviderException if the underlying page provider encounters an error
     */
    List< Page > listEntries( String username ) throws ProviderException;

    /**
     * Checks whether a blog directory exists for the given user.
     *
     * @param username the login name (lowercase) of the blog owner
     * @return {@code true} if the blog directory exists; {@code false} otherwise
     */
    boolean blogExists( String username );

    /**
     * Scans {@code pageDir/blog/<username>/Blog.md} for all users and returns a {@link BlogInfo} snapshot
     * for each blog found, in no guaranteed order.
     *
     * @return list of {@link BlogInfo} records, one per existing blog
     * @throws ProviderException if the underlying page provider encounters an error
     */
    List< BlogInfo > listBlogs() throws ProviderException;
}
