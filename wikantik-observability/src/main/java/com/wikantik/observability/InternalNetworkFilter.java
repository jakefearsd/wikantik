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
package com.wikantik.observability;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Servlet filter that restricts access to mapped endpoints (e.g., {@code /api/health},
 * {@code /metrics}) to requests originating from localhost or RFC 1918 private networks.
 *
 * <p>Allowed IP ranges:</p>
 * <ul>
 *   <li>{@code 127.0.0.0/8} — IPv4 loopback</li>
 *   <li>{@code ::1} — IPv6 loopback</li>
 *   <li>{@code 10.0.0.0/8} — Class A private</li>
 *   <li>{@code 172.16.0.0/12} — Class B private (Docker bridge networks)</li>
 *   <li>{@code 192.168.0.0/16} — Class C private</li>
 * </ul>
 *
 * <p>Uses {@code request.getRemoteAddr()}, which in the Docker deployment reflects the
 * real client IP after Tomcat's {@code RemoteIpValve} processes the {@code CF-Connecting-IP}
 * header from Cloudflare.</p>
 */
public class InternalNetworkFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( InternalNetworkFilter.class );

    private List<CidrRange> allowedRanges;

    @Override
    public void init( final FilterConfig filterConfig ) throws ServletException {
        allowedRanges = List.of(
                new CidrRange( "127.0.0.0", 8 ),
                new CidrRange( "10.0.0.0", 8 ),
                new CidrRange( "172.16.0.0", 12 ),
                new CidrRange( "192.168.0.0", 16 )
        );
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response, final FilterChain chain )
            throws IOException, ServletException {
        final String remoteAddr = request.getRemoteAddr();

        if ( isAllowed( remoteAddr ) ) {
            chain.doFilter( request, response );
        } else {
            LOG.warn( "Blocked access to observability endpoint from external IP: {}", remoteAddr );
            final HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus( HttpServletResponse.SC_FORBIDDEN );
            httpResponse.setContentType( "application/json" );
            httpResponse.setCharacterEncoding( "UTF-8" );
            httpResponse.getWriter().write( "{\"error\":\"Forbidden\"}" );
        }
    }

    @Override
    public void destroy() {
        // no cleanup needed
    }

    /**
     * Tests whether the given IP address is in an allowed range.
     * Package-private for testing.
     */
    boolean isAllowed( final String ipAddress ) {
        if ( ipAddress == null || ipAddress.isBlank() ) {
            return false;
        }

        // IPv6 loopback
        if ( "::1".equals( ipAddress ) || "0:0:0:0:0:0:0:1".equals( ipAddress ) ) {
            return true;
        }

        try {
            final InetAddress addr = InetAddress.getByName( ipAddress );
            final byte[] bytes = addr.getAddress();

            // Only check IPv4 addresses against CIDR ranges
            if ( bytes.length == 4 ) {
                for ( final CidrRange range : allowedRanges ) {
                    if ( range.contains( bytes ) ) {
                        return true;
                    }
                }
            }
        } catch ( final UnknownHostException e ) {
            // Malformed IP — deny
            return false;
        }

        return false;
    }

    /**
     * Represents an IPv4 CIDR range for efficient bit-masking checks.
     */
    static class CidrRange {

        private final int networkInt;
        private final int maskInt;

        CidrRange( final String network, final int prefixLength ) {
            try {
                final byte[] bytes = InetAddress.getByName( network ).getAddress();
                this.networkInt = toInt( bytes );
            } catch ( final UnknownHostException e ) {
                throw new IllegalArgumentException( "Invalid network address: " + network, e );
            }
            this.maskInt = prefixLength == 0 ? 0 : ( -1 << ( 32 - prefixLength ) );
        }

        boolean contains( final byte[] addressBytes ) {
            final int addrInt = toInt( addressBytes );
            return ( addrInt & maskInt ) == ( networkInt & maskInt );
        }

        private static int toInt( final byte[] bytes ) {
            return ( ( bytes[0] & 0xFF ) << 24 )
                    | ( ( bytes[1] & 0xFF ) << 16 )
                    | ( ( bytes[2] & 0xFF ) << 8 )
                    | ( bytes[3] & 0xFF );
        }

    }

}
