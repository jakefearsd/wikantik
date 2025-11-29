# Complete OAuth SSO Implementation Plan for JSPWiki

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Part A: Google Cloud Console Setup](#part-a-google-cloud-console-setup)
3. [Part B: Architecture Overview](#part-b-architecture-overview)
4. [Part C: Implementation Plan](#part-c-implementation-plan)
5. [Part D: Configuration Guide](#part-d-configuration-guide)
6. [Part E: Testing Strategy](#part-e-testing-strategy)
7. [Part F: Security Considerations](#part-f-security-considerations)
8. [Part G: Troubleshooting](#part-g-troubleshooting)
9. [Part H: Cloudflare Tunnel & SquareSpace DNS Setup](#part-h-cloudflare-tunnel--squarespace-dns-setup)

---

## Executive Summary

This document provides a complete implementation plan for adding OAuth 2.0 / OpenID Connect authentication to JSPWiki, enabling users to log in with their Google accounts. The implementation leverages JSPWiki's existing JAAS-based authentication architecture.

**Key Benefits:**
- Users can log in with existing Google accounts
- No password storage required for OAuth users
- Seamless integration with existing permission system
- Optional: Extend to support GitHub, Microsoft, etc.

**Estimated Effort:** 8-10 developer days

---

## Part A: Google Cloud Console Setup

### A.1 Prerequisites

Before starting, ensure you have:
- A Google account with access to Google Cloud Console
- A domain name for your JSPWiki instance (OAuth requires valid redirect URIs)
- HTTPS configured on your server (required for production OAuth)
- Admin access to your JSPWiki deployment

### A.2 Create a Google Cloud Project

1. **Navigate to Google Cloud Console**
   - Go to https://console.cloud.google.com/
   - Sign in with your Google account

2. **Create a New Project**
   - Click the project dropdown at the top of the page
   - Click "New Project"
   - Enter project details:
     - **Project name:** `JSPWiki-OAuth` (or your preferred name)
     - **Organization:** Select your organization or leave as "No organization"
     - **Location:** Select folder if applicable
   - Click "Create"
   - Wait for project creation (30-60 seconds)

3. **Select Your New Project**
   - Use the project dropdown to switch to your newly created project

### A.3 Configure the OAuth Consent Screen

1. **Navigate to OAuth Consent Screen**
   - In the left sidebar, go to "APIs & Services" → "OAuth consent screen"

2. **Select User Type**
   - For internal use (Google Workspace): Select "Internal"
   - For public access: Select "External"
   - Click "Create"

3. **Fill in App Information**
   ```
   App name:                    Your Wiki Name (e.g., "MyCompany Wiki")
   User support email:          your-email@example.com
   App logo:                    (Optional) Upload your wiki logo
   ```

4. **Configure App Domain (Production Only)**
   ```
   Application home page:       https://your-wiki-domain.com/
   Application privacy policy:  https://your-wiki-domain.com/wiki/PrivacyPolicy
   Application terms of service: https://your-wiki-domain.com/wiki/TermsOfService
   ```

5. **Add Authorized Domains**
   ```
   Authorized domains:          your-wiki-domain.com
   ```

6. **Developer Contact Information**
   ```
   Email addresses:             developer@your-domain.com
   ```

7. **Click "Save and Continue"**

### A.4 Configure Scopes

1. **Add Required Scopes**
   - Click "Add or Remove Scopes"
   - Select the following scopes:
     - `openid` - Authenticate using OpenID Connect
     - `email` - View user's email address
     - `profile` - View user's basic profile info (name, picture)
   - Click "Update"

2. **Non-Sensitive Scopes Summary**
   ```
   .../auth/userinfo.email     - See your primary Google Account email address
   .../auth/userinfo.profile   - See your personal info
   openid                      - Associate you with your personal info
   ```

3. **Click "Save and Continue"**

### A.5 Add Test Users (External Apps Only)

If you selected "External" user type:

1. **Add Test Users**
   - Click "Add Users"
   - Enter email addresses of users who can test before verification
   - You can add up to 100 test users

2. **Click "Save and Continue"**

### A.6 Create OAuth 2.0 Credentials

1. **Navigate to Credentials**
   - Go to "APIs & Services" → "Credentials"

2. **Create OAuth Client ID**
   - Click "Create Credentials" → "OAuth client ID"

3. **Configure Application Type**
   - Select "Web application"

4. **Name Your Client**
   ```
   Name:                        JSPWiki OAuth Client
   ```

5. **Add Authorized JavaScript Origins**
   ```
   # Development
   http://localhost:8080

   # Production
   https://your-wiki-domain.com
   ```

6. **Add Authorized Redirect URIs**
   ```
   # Development
   http://localhost:8080/JSPWiki/oauth/callback

   # Production
   https://your-wiki-domain.com/JSPWiki/oauth/callback
   ```

7. **Click "Create"**

8. **Save Your Credentials**
   - A dialog will appear with your Client ID and Client Secret
   - **IMPORTANT:** Copy these values immediately and store securely
   ```
   Client ID:     xxxxxxxxxxxx-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.apps.googleusercontent.com
   Client Secret: GOCSPX-xxxxxxxxxxxxxxxxxxxxxxxxxxxx
   ```

### A.7 Enable Required APIs

1. **Navigate to API Library**
   - Go to "APIs & Services" → "Library"

2. **Enable Google People API** (optional, for additional profile info)
   - Search for "Google People API"
   - Click on it
   - Click "Enable"

### A.8 App Verification (Production Only)

For external apps, you must complete verification:

1. **Prepare Verification Materials**
   - Privacy policy page on your domain
   - YouTube video demonstrating OAuth flow (for sensitive scopes)
   - Authorized domains proof

2. **Submit for Verification**
   - Return to OAuth consent screen
   - Click "Prepare for verification"
   - Complete the verification form
   - Submit and wait for review (can take several weeks)

### A.9 Checklist: Google Cloud Setup Complete

- [ ] Google Cloud project created
- [ ] OAuth consent screen configured
- [ ] Scopes added (openid, email, profile)
- [ ] OAuth 2.0 client credentials created
- [ ] Redirect URIs configured for dev and production
- [ ] Client ID and Client Secret saved securely
- [ ] (Production) Verification submitted

---

## Part B: Architecture Overview

### B.1 High-Level OAuth Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              OAuth 2.0 / OIDC Flow                          │
└─────────────────────────────────────────────────────────────────────────────┘

 ┌──────────┐     1. Click "Login with Google"     ┌──────────────────────────┐
 │          │ ─────────────────────────────────────▶│                          │
 │  Browser │                                       │  JSPWiki                 │
 │          │◀──── 2. Redirect to Google ──────────│  OAuthStartServlet       │
 └──────────┘                                       └──────────────────────────┘
      │
      │ 3. User logs in to Google
      ▼
 ┌──────────────────────┐
 │                      │
 │  Google OAuth        │
 │  Consent Screen      │
 │                      │
 └──────────────────────┘
      │
      │ 4. User grants permission
      ▼
 ┌──────────┐     5. Redirect with auth code        ┌──────────────────────────┐
 │          │ ─────────────────────────────────────▶│                          │
 │  Browser │                                       │  OAuthCallbackServlet    │
 │          │                                       │  /oauth/callback         │
 └──────────┘                                       └──────────────────────────┘
                                                            │
                                                            │ 6. Exchange code for tokens
                                                            ▼
                                                    ┌──────────────────────────┐
                                                    │  Google Token Endpoint   │
                                                    │  https://oauth2.google   │
                                                    │  apis.com/token          │
                                                    └──────────────────────────┘
                                                            │
                                                            │ 7. Return ID token + access token
                                                            ▼
                                                    ┌──────────────────────────┐
                                                    │  Validate ID Token       │
                                                    │  Extract email, name     │
                                                    └──────────────────────────┘
                                                            │
                                                            │ 8. Create/lookup user
                                                            ▼
                                                    ┌──────────────────────────┐
                                                    │  JAAS Login              │
                                                    │  OAuthLoginModule        │
                                                    └──────────────────────────┘
                                                            │
                                                            │ 9. Fire LOGIN_AUTHENTICATED
                                                            ▼
 ┌──────────┐     10. Redirect to wiki page         ┌──────────────────────────┐
 │          │◀─────────────────────────────────────│                          │
 │  Browser │                                       │  WikiSession updated     │
 │          │                                       │  User authenticated      │
 └──────────┘                                       └──────────────────────────┘
```

### B.2 Component Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         OAuth Module Architecture                            │
└─────────────────────────────────────────────────────────────────────────────┘

org.apache.wiki.auth.oauth/
├── OAuthConfiguration.java          # Loads OAuth settings from properties
├── OAuthManager.java                 # Manages OAuth providers and flow
├── OAuthUserInfo.java                # DTO for user info from providers
│
├── provider/
│   ├── OAuthProvider.java            # Interface for OAuth providers
│   ├── GoogleOAuthProvider.java      # Google implementation
│   └── GitHubOAuthProvider.java      # (Future) GitHub implementation
│
├── login/
│   ├── OAuthLoginModule.java         # JAAS LoginModule for OAuth
│   ├── OAuthCallbackHandler.java     # JAAS CallbackHandler
│   └── OAuthCallback.java            # Custom callback for OAuth data
│
└── servlet/
    ├── OAuthStartServlet.java        # Initiates OAuth flow (/oauth/google)
    └── OAuthCallbackServlet.java     # Handles callback (/oauth/callback)
```

### B.3 Integration Points

| Component | Integration Method | Description |
|-----------|-------------------|-------------|
| WikiSession | Event firing | LOGIN_AUTHENTICATED event triggers session update |
| UserDatabase | Direct API | findByEmail(), save() for user management |
| AuthenticationManager | JAAS | OAuthLoginModule plugs into existing framework |
| web.xml | Servlet registration | New servlets for OAuth endpoints |
| jspwiki.properties | Configuration | OAuth credentials and settings |
| LoginContent.jsp | UI buttons | "Login with Google" button |

---

## Part C: Implementation Plan

### C.1 New Dependencies (pom.xml)

Add to `jspwiki-main/pom.xml`:

```xml
<!-- OAuth 2.0 / OpenID Connect -->
<dependency>
    <groupId>com.google.api-client</groupId>
    <artifactId>google-api-client</artifactId>
    <version>2.2.0</version>
</dependency>

<dependency>
    <groupId>com.google.oauth-client</groupId>
    <artifactId>google-oauth-client-jetty</artifactId>
    <version>1.34.1</version>
    <exclusions>
        <exclusion>
            <groupId>org.mortbay.jetty</groupId>
            <artifactId>*</artifactId>
        </exclusion>
    </exclusions>
</dependency>

<dependency>
    <groupId>com.google.http-client</groupId>
    <artifactId>google-http-client-jackson2</artifactId>
    <version>1.43.3</version>
</dependency>

<!-- JWT for ID token validation -->
<dependency>
    <groupId>com.auth0</groupId>
    <artifactId>java-jwt</artifactId>
    <version>4.4.0</version>
</dependency>

<dependency>
    <groupId>com.auth0</groupId>
    <artifactId>jwks-rsa</artifactId>
    <version>0.22.1</version>
</dependency>
```

Or, add version properties to root `pom.xml`:

```xml
<google-api-client.version>2.2.0</google-api-client.version>
<google-oauth-client.version>1.34.1</google-oauth-client.version>
<google-http-client.version>1.43.3</google-http-client.version>
<auth0-java-jwt.version>4.4.0</auth0-java-jwt.version>
<auth0-jwks-rsa.version>0.22.1</auth0-jwks-rsa.version>
```

### C.2 File-by-File Implementation

#### C.2.1 OAuthConfiguration.java

**Location:** `jspwiki-main/src/main/java/org/apache/wiki/auth/oauth/OAuthConfiguration.java`

**Purpose:** Centralized configuration management for OAuth settings

```java
package org.apache.wiki.auth.oauth;

import org.apache.wiki.api.core.Engine;
import java.util.Properties;

/**
 * Configuration holder for OAuth settings loaded from jspwiki.properties.
 */
public class OAuthConfiguration {

    // Property keys
    public static final String PROP_OAUTH_ENABLED = "jspwiki.oauth.enabled";
    public static final String PROP_OAUTH_AUTO_CREATE = "jspwiki.oauth.autoCreateUsers";

    public static final String PROP_GOOGLE_ENABLED = "jspwiki.oauth.google.enabled";
    public static final String PROP_GOOGLE_CLIENT_ID = "jspwiki.oauth.google.clientId";
    public static final String PROP_GOOGLE_CLIENT_SECRET = "jspwiki.oauth.google.clientSecret";
    public static final String PROP_GOOGLE_SCOPES = "jspwiki.oauth.google.scopes";

    // Google OAuth endpoints (constants)
    public static final String GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    public static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    public static final String GOOGLE_USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";
    public static final String GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";

    private final boolean oauthEnabled;
    private final boolean autoCreateUsers;
    private final boolean googleEnabled;
    private final String googleClientId;
    private final String googleClientSecret;
    private final String googleScopes;

    public OAuthConfiguration(Engine engine) {
        Properties props = engine.getWikiProperties();

        this.oauthEnabled = Boolean.parseBoolean(
            props.getProperty(PROP_OAUTH_ENABLED, "false"));
        this.autoCreateUsers = Boolean.parseBoolean(
            props.getProperty(PROP_OAUTH_AUTO_CREATE, "true"));

        this.googleEnabled = Boolean.parseBoolean(
            props.getProperty(PROP_GOOGLE_ENABLED, "false"));
        this.googleClientId = props.getProperty(PROP_GOOGLE_CLIENT_ID, "");
        this.googleClientSecret = props.getProperty(PROP_GOOGLE_CLIENT_SECRET, "");
        this.googleScopes = props.getProperty(PROP_GOOGLE_SCOPES,
            "openid email profile");
    }

    // Getters
    public boolean isOAuthEnabled() { return oauthEnabled; }
    public boolean isAutoCreateUsers() { return autoCreateUsers; }
    public boolean isGoogleEnabled() { return googleEnabled && oauthEnabled; }
    public String getGoogleClientId() { return googleClientId; }
    public String getGoogleClientSecret() { return googleClientSecret; }
    public String getGoogleScopes() { return googleScopes; }

    /**
     * Validates that required configuration is present.
     */
    public void validate() throws IllegalStateException {
        if (oauthEnabled && googleEnabled) {
            if (googleClientId.isEmpty()) {
                throw new IllegalStateException(
                    "Google OAuth enabled but " + PROP_GOOGLE_CLIENT_ID + " not set");
            }
            if (googleClientSecret.isEmpty()) {
                throw new IllegalStateException(
                    "Google OAuth enabled but " + PROP_GOOGLE_CLIENT_SECRET + " not set");
            }
        }
    }
}
```

#### C.2.2 OAuthUserInfo.java

**Location:** `jspwiki-main/src/main/java/org/apache/wiki/auth/oauth/OAuthUserInfo.java`

**Purpose:** Data transfer object for OAuth user information

```java
package org.apache.wiki.auth.oauth;

import java.io.Serializable;

/**
 * Holds user information retrieved from an OAuth provider.
 */
public class OAuthUserInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String providerId;      // Unique ID from provider
    private final String providerName;    // "google", "github", etc.
    private final String email;
    private final String fullName;
    private final String pictureUrl;
    private final boolean emailVerified;

    private OAuthUserInfo(Builder builder) {
        this.providerId = builder.providerId;
        this.providerName = builder.providerName;
        this.email = builder.email;
        this.fullName = builder.fullName;
        this.pictureUrl = builder.pictureUrl;
        this.emailVerified = builder.emailVerified;
    }

    // Getters
    public String getProviderId() { return providerId; }
    public String getProviderName() { return providerName; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public String getPictureUrl() { return pictureUrl; }
    public boolean isEmailVerified() { return emailVerified; }

    /**
     * Generates a unique login name for this OAuth user.
     * Format: provider_localpart (e.g., "google_john.smith")
     */
    public String generateLoginName() {
        String localPart = email.substring(0, email.indexOf('@'));
        // Sanitize: only alphanumeric and dots
        localPart = localPart.replaceAll("[^a-zA-Z0-9.]", "_");
        return providerName + "_" + localPart;
    }

    public static class Builder {
        private String providerId;
        private String providerName;
        private String email;
        private String fullName;
        private String pictureUrl;
        private boolean emailVerified;

        public Builder providerId(String providerId) {
            this.providerId = providerId;
            return this;
        }

        public Builder providerName(String providerName) {
            this.providerName = providerName;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public Builder fullName(String fullName) {
            this.fullName = fullName;
            return this;
        }

        public Builder pictureUrl(String pictureUrl) {
            this.pictureUrl = pictureUrl;
            return this;
        }

        public Builder emailVerified(boolean emailVerified) {
            this.emailVerified = emailVerified;
            return this;
        }

        public OAuthUserInfo build() {
            return new OAuthUserInfo(this);
        }
    }
}
```

#### C.2.3 OAuthProvider.java (Interface)

**Location:** `jspwiki-main/src/main/java/org/apache/wiki/auth/oauth/provider/OAuthProvider.java`

```java
package org.apache.wiki.auth.oauth.provider;

import org.apache.wiki.auth.oauth.OAuthConfiguration;
import org.apache.wiki.auth.oauth.OAuthUserInfo;

/**
 * Interface for OAuth 2.0 providers.
 */
public interface OAuthProvider {

    /**
     * Returns the provider name (e.g., "google", "github").
     */
    String getName();

    /**
     * Builds the authorization URL for the OAuth flow.
     *
     * @param redirectUri The callback URI
     * @param state CSRF protection state parameter
     * @return Full authorization URL
     */
    String buildAuthorizationUrl(String redirectUri, String state);

    /**
     * Exchanges an authorization code for tokens.
     *
     * @param code The authorization code from callback
     * @param redirectUri The same redirect URI used in authorization
     * @return Token response including ID token and access token
     */
    TokenResponse exchangeCode(String code, String redirectUri) throws OAuthException;

    /**
     * Validates the ID token and extracts user information.
     *
     * @param idToken The JWT ID token from token exchange
     * @return User information extracted from the token
     */
    OAuthUserInfo validateAndExtractUser(String idToken) throws OAuthException;

    /**
     * Gets user info using the access token (backup if ID token incomplete).
     *
     * @param accessToken The access token from token exchange
     * @return User information from userinfo endpoint
     */
    OAuthUserInfo getUserInfo(String accessToken) throws OAuthException;

    /**
     * Token response holder.
     */
    record TokenResponse(
        String accessToken,
        String idToken,
        String refreshToken,
        long expiresIn
    ) {}

    /**
     * OAuth exception for provider errors.
     */
    class OAuthException extends Exception {
        public OAuthException(String message) {
            super(message);
        }

        public OAuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
```

#### C.2.4 GoogleOAuthProvider.java

**Location:** `jspwiki-main/src/main/java/org/apache/wiki/auth/oauth/provider/GoogleOAuthProvider.java`

```java
package org.apache.wiki.auth.oauth.provider;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.auth.oauth.OAuthConfiguration;
import org.apache.wiki.auth.oauth.OAuthUserInfo;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Google OAuth 2.0 / OpenID Connect provider implementation.
 */
public class GoogleOAuthProvider implements OAuthProvider {

    private static final Logger LOG = LogManager.getLogger(GoogleOAuthProvider.class);
    private static final String NAME = "google";

    private final OAuthConfiguration config;
    private final HttpClient httpClient;
    private final JwkProvider jwkProvider;
    private final Gson gson;

    public GoogleOAuthProvider(OAuthConfiguration config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.jwkProvider = new JwkProviderBuilder("https://www.googleapis.com/oauth2/v3/certs")
            .cached(10, 24, TimeUnit.HOURS)
            .rateLimited(10, 1, TimeUnit.MINUTES)
            .build();
        this.gson = new Gson();
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String buildAuthorizationUrl(String redirectUri, String state) {
        return OAuthConfiguration.GOOGLE_AUTH_URL + "?" +
            "client_id=" + encode(config.getGoogleClientId()) +
            "&redirect_uri=" + encode(redirectUri) +
            "&response_type=code" +
            "&scope=" + encode(config.getGoogleScopes()) +
            "&state=" + encode(state) +
            "&access_type=offline" +  // Get refresh token
            "&prompt=select_account";  // Always show account selector
    }

    @Override
    public TokenResponse exchangeCode(String code, String redirectUri) throws OAuthException {
        try {
            String requestBody =
                "code=" + encode(code) +
                "&client_id=" + encode(config.getGoogleClientId()) +
                "&client_secret=" + encode(config.getGoogleClientSecret()) +
                "&redirect_uri=" + encode(redirectUri) +
                "&grant_type=authorization_code";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OAuthConfiguration.GOOGLE_TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.error("Token exchange failed: {} - {}",
                    response.statusCode(), response.body());
                throw new OAuthException("Token exchange failed: " + response.statusCode());
            }

            JsonObject json = gson.fromJson(response.body(), JsonObject.class);

            return new TokenResponse(
                json.get("access_token").getAsString(),
                json.has("id_token") ? json.get("id_token").getAsString() : null,
                json.has("refresh_token") ? json.get("refresh_token").getAsString() : null,
                json.get("expires_in").getAsLong()
            );

        } catch (IOException | InterruptedException e) {
            throw new OAuthException("Token exchange failed", e);
        }
    }

    @Override
    public OAuthUserInfo validateAndExtractUser(String idToken) throws OAuthException {
        try {
            // Decode without verification first to get key ID
            DecodedJWT jwt = JWT.decode(idToken);

            // Get the public key from Google's JWKS
            Jwk jwk = jwkProvider.get(jwt.getKeyId());
            Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), null);

            // Verify the token
            algorithm.verify(jwt);

            // Validate claims
            String issuer = jwt.getIssuer();
            if (!"https://accounts.google.com".equals(issuer) &&
                !"accounts.google.com".equals(issuer)) {
                throw new OAuthException("Invalid issuer: " + issuer);
            }

            String audience = jwt.getAudience().get(0);
            if (!config.getGoogleClientId().equals(audience)) {
                throw new OAuthException("Invalid audience: " + audience);
            }

            // Check expiration
            if (jwt.getExpiresAt().getTime() < System.currentTimeMillis()) {
                throw new OAuthException("Token expired");
            }

            // Extract user info
            return new OAuthUserInfo.Builder()
                .providerId(jwt.getSubject())
                .providerName(NAME)
                .email(jwt.getClaim("email").asString())
                .fullName(jwt.getClaim("name").asString())
                .pictureUrl(jwt.getClaim("picture").asString())
                .emailVerified(jwt.getClaim("email_verified").asBoolean())
                .build();

        } catch (Exception e) {
            throw new OAuthException("ID token validation failed", e);
        }
    }

    @Override
    public OAuthUserInfo getUserInfo(String accessToken) throws OAuthException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OAuthConfiguration.GOOGLE_USERINFO_URL))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new OAuthException("UserInfo request failed: " + response.statusCode());
            }

            JsonObject json = gson.fromJson(response.body(), JsonObject.class);

            return new OAuthUserInfo.Builder()
                .providerId(json.get("sub").getAsString())
                .providerName(NAME)
                .email(json.get("email").getAsString())
                .fullName(json.has("name") ? json.get("name").getAsString() : null)
                .pictureUrl(json.has("picture") ? json.get("picture").getAsString() : null)
                .emailVerified(json.has("email_verified") &&
                    json.get("email_verified").getAsBoolean())
                .build();

        } catch (IOException | InterruptedException e) {
            throw new OAuthException("UserInfo request failed", e);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
```

#### C.2.5 OAuthCallback.java

**Location:** `jspwiki-main/src/main/java/org/apache/wiki/auth/login/OAuthCallback.java`

```java
package org.apache.wiki.auth.login;

import org.apache.wiki.auth.oauth.OAuthUserInfo;
import javax.security.auth.callback.Callback;

/**
 * JAAS Callback for passing OAuth user information to the LoginModule.
 */
public class OAuthCallback implements Callback {

    private OAuthUserInfo userInfo;

    public void setUserInfo(OAuthUserInfo userInfo) {
        this.userInfo = userInfo;
    }

    public OAuthUserInfo getUserInfo() {
        return userInfo;
    }
}
```

#### C.2.6 OAuthCallbackHandler.java

**Location:** `jspwiki-main/src/main/java/org/apache/wiki/auth/login/OAuthCallbackHandler.java`

```java
package org.apache.wiki.auth.login;

import org.apache.wiki.api.core.Engine;
import org.apache.wiki.auth.UserManager;
import org.apache.wiki.auth.oauth.OAuthUserInfo;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * JAAS CallbackHandler for OAuth authentication.
 * Provides OAuth user info and engine/request to the LoginModule.
 */
public class OAuthCallbackHandler implements CallbackHandler {

    private final Engine engine;
    private final HttpServletRequest request;
    private final OAuthUserInfo userInfo;

    public OAuthCallbackHandler(Engine engine, HttpServletRequest request,
                                OAuthUserInfo userInfo) {
        this.engine = engine;
        this.request = request;
        this.userInfo = userInfo;
    }

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        for (Callback callback : callbacks) {
            if (callback instanceof OAuthCallback oauthCallback) {
                oauthCallback.setUserInfo(userInfo);
            } else if (callback instanceof HttpRequestCallback httpCallback) {
                httpCallback.setRequest(request);
            } else if (callback instanceof WikiEngineCallback engineCallback) {
                engineCallback.setEngine(engine);
            } else if (callback instanceof UserDatabaseCallback dbCallback) {
                dbCallback.setUserDatabase(
                    engine.getManager(UserManager.class).getUserDatabase());
            } else {
                throw new UnsupportedCallbackException(callback);
            }
        }
    }
}
```

#### C.2.7 OAuthLoginModule.java

**Location:** `jspwiki-main/src/main/java/org/apache/wiki/auth/login/OAuthLoginModule.java`

```java
package org.apache.wiki.auth.login;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.auth.NoSuchPrincipalException;
import org.apache.wiki.auth.WikiPrincipal;
import org.apache.wiki.auth.oauth.OAuthUserInfo;
import org.apache.wiki.auth.user.UserDatabase;
import org.apache.wiki.auth.user.UserProfile;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.io.Serializable;
import java.util.Date;
import java.util.Map;

/**
 * JAAS LoginModule for OAuth authentication.
 *
 * Handles user lookup and creation for OAuth-authenticated users.
 * Users are looked up by email address; if not found and auto-create
 * is enabled, a new user is created.
 */
public class OAuthLoginModule extends AbstractLoginModule {

    private static final Logger LOG = LogManager.getLogger(OAuthLoginModule.class);

    // Option for auto-creating users
    public static final String OPTION_AUTO_CREATE = "autoCreateUsers";

    // Attribute keys for OAuth metadata
    public static final String ATTR_OAUTH_PROVIDER = "oauth.provider";
    public static final String ATTR_OAUTH_PROVIDER_ID = "oauth.providerId";
    public static final String ATTR_OAUTH_PICTURE_URL = "oauth.pictureUrl";

    @Override
    public boolean login() throws LoginException {
        // Get OAuth user info via callback
        OAuthCallback oauthCallback = new OAuthCallback();
        UserDatabaseCallback dbCallback = new UserDatabaseCallback();

        try {
            m_handler.handle(new Callback[] { oauthCallback, dbCallback });
        } catch (IOException | UnsupportedCallbackException e) {
            LOG.error("Callback handling failed", e);
            throw new LoginException("OAuth callback failed: " + e.getMessage());
        }

        OAuthUserInfo userInfo = oauthCallback.getUserInfo();
        UserDatabase db = dbCallback.getUserDatabase();

        if (userInfo == null) {
            throw new FailedLoginException("No OAuth user info provided");
        }

        if (db == null) {
            throw new FailedLoginException("No user database available");
        }

        // Require verified email
        if (!userInfo.isEmailVerified()) {
            throw new FailedLoginException("Email address not verified by provider");
        }

        try {
            // Try to find existing user by email
            UserProfile profile = findOrCreateUser(db, userInfo);

            // Update OAuth metadata
            updateOAuthMetadata(profile, userInfo);

            // Save profile to persist any updates
            db.save(profile);

            // Add principal for the login name
            m_principals.add(new WikiPrincipal(
                profile.getLoginName(), WikiPrincipal.LOGIN_NAME));

            LOG.info("OAuth login successful for user: {}", profile.getLoginName());
            return true;

        } catch (Exception e) {
            LOG.error("OAuth login failed", e);
            throw new FailedLoginException("OAuth login failed: " + e.getMessage());
        }
    }

    /**
     * Finds an existing user by email or creates a new one.
     */
    private UserProfile findOrCreateUser(UserDatabase db, OAuthUserInfo userInfo)
            throws Exception {

        try {
            // First, try to find by email
            return db.findByEmail(userInfo.getEmail());

        } catch (NoSuchPrincipalException e) {
            // User doesn't exist - check if auto-create is enabled
            boolean autoCreate = Boolean.parseBoolean(
                (String) m_options.getOrDefault(OPTION_AUTO_CREATE, "true"));

            if (!autoCreate) {
                throw new FailedLoginException(
                    "User not found and auto-registration is disabled");
            }

            // Create new user
            LOG.info("Creating new OAuth user for email: {}", userInfo.getEmail());

            UserProfile profile = db.newProfile();
            profile.setLoginName(userInfo.generateLoginName());
            profile.setEmail(userInfo.getEmail());
            profile.setFullname(userInfo.getFullName() != null ?
                userInfo.getFullName() : userInfo.getEmail());
            profile.setPassword(null);  // No password for OAuth users
            profile.setCreated(new Date());

            return profile;
        }
    }

    /**
     * Updates OAuth metadata attributes on the user profile.
     */
    private void updateOAuthMetadata(UserProfile profile, OAuthUserInfo userInfo) {
        Map<String, Serializable> attrs = profile.getAttributes();
        attrs.put(ATTR_OAUTH_PROVIDER, userInfo.getProviderName());
        attrs.put(ATTR_OAUTH_PROVIDER_ID, userInfo.getProviderId());

        if (userInfo.getPictureUrl() != null) {
            attrs.put(ATTR_OAUTH_PICTURE_URL, userInfo.getPictureUrl());
        }

        // Update full name if it changed
        if (userInfo.getFullName() != null && !userInfo.getFullName().isEmpty()) {
            String existingName = profile.getFullname();
            // Only update if empty or was auto-generated from email
            if (existingName == null || existingName.contains("@")) {
                profile.setFullname(userInfo.getFullName());
            }
        }
    }
}
```

#### C.2.8 OAuthStartServlet.java

**Location:** `jspwiki-main/src/main/java/org/apache/wiki/auth/oauth/servlet/OAuthStartServlet.java`

```java
package org.apache.wiki.auth.oauth.servlet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.Wiki;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.auth.oauth.OAuthConfiguration;
import org.apache.wiki.auth.oauth.provider.GoogleOAuthProvider;
import org.apache.wiki.auth.oauth.provider.OAuthProvider;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Initiates OAuth authentication flow.
 *
 * URL pattern: /oauth/{provider}
 * Example: /oauth/google
 *
 * Parameters:
 *   redirect - Page to return to after login (optional)
 */
public class OAuthStartServlet extends HttpServlet {

    private static final Logger LOG = LogManager.getLogger(OAuthStartServlet.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    // Session attribute for CSRF state
    public static final String ATTR_OAUTH_STATE = "oauth_state";
    public static final String ATTR_OAUTH_REDIRECT = "oauth_redirect";

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        Engine engine = Wiki.engine().find(request);
        OAuthConfiguration config = new OAuthConfiguration(engine);

        // Check if OAuth is enabled
        if (!config.isOAuthEnabled()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND,
                "OAuth is not enabled");
            return;
        }

        // Extract provider from URL path
        String pathInfo = request.getPathInfo();
        if (pathInfo == null || pathInfo.length() < 2) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                "Provider not specified");
            return;
        }

        String providerName = pathInfo.substring(1).toLowerCase();

        // Get the appropriate provider
        OAuthProvider provider = getProvider(config, providerName);
        if (provider == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                "Unknown provider: " + providerName);
            return;
        }

        // Generate CSRF state token
        byte[] stateBytes = new byte[32];
        RANDOM.nextBytes(stateBytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);

        // Store state and redirect URL in session
        HttpSession session = request.getSession(true);
        session.setAttribute(ATTR_OAUTH_STATE, state);

        String redirectPage = request.getParameter("redirect");
        if (redirectPage != null && !redirectPage.isEmpty()) {
            session.setAttribute(ATTR_OAUTH_REDIRECT, redirectPage);
        }

        // Build callback URL
        String callbackUrl = buildCallbackUrl(request);

        // Build authorization URL and redirect
        String authUrl = provider.buildAuthorizationUrl(callbackUrl, state);

        LOG.debug("Redirecting to OAuth provider: {}", providerName);
        response.sendRedirect(authUrl);
    }

    private OAuthProvider getProvider(OAuthConfiguration config, String name) {
        return switch (name) {
            case "google" -> config.isGoogleEnabled() ?
                new GoogleOAuthProvider(config) : null;
            // Future: case "github" -> ...
            default -> null;
        };
    }

    private String buildCallbackUrl(HttpServletRequest request) {
        StringBuilder url = new StringBuilder();
        url.append(request.getScheme()).append("://");
        url.append(request.getServerName());

        int port = request.getServerPort();
        if (("http".equals(request.getScheme()) && port != 80) ||
            ("https".equals(request.getScheme()) && port != 443)) {
            url.append(":").append(port);
        }

        url.append(request.getContextPath());
        url.append("/oauth/callback");

        return url.toString();
    }
}
```

#### C.2.9 OAuthCallbackServlet.java

**Location:** `jspwiki-main/src/main/java/org/apache/wiki/auth/oauth/servlet/OAuthCallbackServlet.java`

```java
package org.apache.wiki.auth.oauth.servlet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.Wiki;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.core.Session;
import org.apache.wiki.auth.AuthenticationManager;
import org.apache.wiki.auth.SessionMonitor;
import org.apache.wiki.auth.WikiSecurityException;
import org.apache.wiki.auth.login.OAuthCallbackHandler;
import org.apache.wiki.auth.login.OAuthLoginModule;
import org.apache.wiki.auth.oauth.OAuthConfiguration;
import org.apache.wiki.auth.oauth.OAuthUserInfo;
import org.apache.wiki.auth.oauth.provider.GoogleOAuthProvider;
import org.apache.wiki.auth.oauth.provider.OAuthProvider;
import org.apache.wiki.auth.oauth.provider.OAuthProvider.TokenResponse;
import org.apache.wiki.event.WikiSecurityEvent;
import org.apache.wiki.event.WikiEventManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Handles OAuth callback from providers.
 *
 * URL pattern: /oauth/callback
 *
 * Query parameters:
 *   code  - Authorization code from provider
 *   state - CSRF state token
 *   error - Error code if authorization failed
 */
public class OAuthCallbackServlet extends HttpServlet {

    private static final Logger LOG = LogManager.getLogger(OAuthCallbackServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        Engine engine = Wiki.engine().find(request);
        HttpSession httpSession = request.getSession(false);

        // Check for OAuth error
        String error = request.getParameter("error");
        if (error != null) {
            LOG.warn("OAuth error from provider: {}", error);
            redirectToLogin(request, response, "OAuth authentication failed: " + error);
            return;
        }

        // Get authorization code
        String code = request.getParameter("code");
        if (code == null || code.isEmpty()) {
            redirectToLogin(request, response, "No authorization code received");
            return;
        }

        // Validate CSRF state
        String state = request.getParameter("state");
        if (httpSession == null) {
            redirectToLogin(request, response, "Session expired");
            return;
        }

        String savedState = (String) httpSession.getAttribute(OAuthStartServlet.ATTR_OAUTH_STATE);
        if (savedState == null || !savedState.equals(state)) {
            LOG.warn("CSRF state mismatch. Expected: {}, Got: {}", savedState, state);
            redirectToLogin(request, response, "Security validation failed");
            return;
        }

        // Clear the state from session
        httpSession.removeAttribute(OAuthStartServlet.ATTR_OAUTH_STATE);

        // Get redirect page
        String redirectPage = (String) httpSession.getAttribute(OAuthStartServlet.ATTR_OAUTH_REDIRECT);
        httpSession.removeAttribute(OAuthStartServlet.ATTR_OAUTH_REDIRECT);

        try {
            // Process the OAuth callback
            OAuthUserInfo userInfo = processOAuthCallback(engine, request, code);

            // Perform JAAS login
            performLogin(engine, request, userInfo);

            // Redirect to original page or front page
            if (redirectPage == null || redirectPage.isEmpty()) {
                redirectPage = engine.getFrontPage();
            }

            String wikiUrl = engine.getWikiProperties().getProperty("jspwiki.baseURL",
                request.getContextPath());
            response.sendRedirect(wikiUrl + "Wiki.jsp?page=" + redirectPage);

        } catch (Exception e) {
            LOG.error("OAuth callback processing failed", e);
            redirectToLogin(request, response, "Authentication failed: " + e.getMessage());
        }
    }

    /**
     * Processes the OAuth callback: exchanges code for tokens and gets user info.
     */
    private OAuthUserInfo processOAuthCallback(Engine engine, HttpServletRequest request,
            String code) throws OAuthProvider.OAuthException {

        OAuthConfiguration config = new OAuthConfiguration(engine);

        // For now, assume Google (in production, you'd detect from state or session)
        OAuthProvider provider = new GoogleOAuthProvider(config);

        // Build callback URL (same as used in authorization)
        String callbackUrl = buildCallbackUrl(request);

        // Exchange code for tokens
        TokenResponse tokens = provider.exchangeCode(code, callbackUrl);

        // Get user info from ID token (preferred) or access token
        OAuthUserInfo userInfo;
        if (tokens.idToken() != null) {
            userInfo = provider.validateAndExtractUser(tokens.idToken());
        } else {
            userInfo = provider.getUserInfo(tokens.accessToken());
        }

        LOG.info("OAuth authentication successful for: {}", userInfo.getEmail());
        return userInfo;
    }

    /**
     * Performs JAAS login with the OAuth user info.
     */
    private void performLogin(Engine engine, HttpServletRequest request,
            OAuthUserInfo userInfo) throws WikiSecurityException {

        OAuthConfiguration config = new OAuthConfiguration(engine);
        AuthenticationManager authMgr = engine.getManager(AuthenticationManager.class);
        Session wikiSession = SessionMonitor.getInstance(engine).find(request.getSession());

        // Create callback handler with OAuth user info
        OAuthCallbackHandler handler = new OAuthCallbackHandler(engine, request, userInfo);

        // Configure login module options
        Map<String, String> options = new HashMap<>();
        options.put(OAuthLoginModule.OPTION_AUTO_CREATE,
            String.valueOf(config.isAutoCreateUsers()));

        // Perform JAAS login
        Set<Principal> principals = authMgr.doJAASLogin(
            OAuthLoginModule.class, handler, options);

        if (principals.isEmpty()) {
            throw new WikiSecurityException("OAuth login failed - no principals returned");
        }

        // Fire login event to update WikiSession
        Principal loginPrincipal = principals.iterator().next();
        WikiEventManager.fireEvent(authMgr,
            new WikiSecurityEvent(authMgr, WikiSecurityEvent.LOGIN_AUTHENTICATED,
                loginPrincipal, wikiSession));

        // Add all principals
        for (Principal principal : principals) {
            WikiEventManager.fireEvent(authMgr,
                new WikiSecurityEvent(authMgr, WikiSecurityEvent.PRINCIPAL_ADD,
                    principal, wikiSession));
        }
    }

    private void redirectToLogin(HttpServletRequest request,
            HttpServletResponse response, String error) throws IOException {

        String loginUrl = request.getContextPath() + "/Login.jsp";
        if (error != null) {
            loginUrl += "?error=" + java.net.URLEncoder.encode(error, "UTF-8");
        }
        response.sendRedirect(loginUrl);
    }

    private String buildCallbackUrl(HttpServletRequest request) {
        StringBuilder url = new StringBuilder();
        url.append(request.getScheme()).append("://");
        url.append(request.getServerName());

        int port = request.getServerPort();
        if (("http".equals(request.getScheme()) && port != 80) ||
            ("https".equals(request.getScheme()) && port != 443)) {
            url.append(":").append(port);
        }

        url.append(request.getContextPath());
        url.append("/oauth/callback");

        return url.toString();
    }
}
```

#### C.2.10 web.xml Updates

**Location:** `jspwiki-war/src/main/webapp/WEB-INF/web.xml`

Add the following servlet definitions:

```xml
<!-- OAuth Start Servlet -->
<servlet>
    <servlet-name>OAuthStartServlet</servlet-name>
    <servlet-class>org.apache.wiki.auth.oauth.servlet.OAuthStartServlet</servlet-class>
</servlet>

<!-- OAuth Callback Servlet -->
<servlet>
    <servlet-name>OAuthCallbackServlet</servlet-name>
    <servlet-class>org.apache.wiki.auth.oauth.servlet.OAuthCallbackServlet</servlet-class>
</servlet>

<servlet-mapping>
    <servlet-name>OAuthStartServlet</servlet-name>
    <url-pattern>/oauth/*</url-pattern>
</servlet-mapping>

<servlet-mapping>
    <servlet-name>OAuthCallbackServlet</servlet-name>
    <url-pattern>/oauth/callback</url-pattern>
</servlet-mapping>
```

**Note:** The callback mapping must come before the general `/oauth/*` mapping to ensure proper routing.

#### C.2.11 LoginContent.jsp Updates

**Location:** `jspwiki-war/src/main/webapp/templates/default/LoginContent.jsp`

Add OAuth login buttons after the regular login form (around line 98, after the `<hr />`):

```jsp
<%-- OAuth Login Options --%>
<c:if test="${oauthEnabled}">
<div class="oauth-login-options">
  <p class="text-center"><fmt:message key="login.oauth.or"/></p>

  <c:if test="${googleOAuthEnabled}">
  <a class="btn btn-default btn-block oauth-btn oauth-google"
     href="<wiki:Link format='url' jsp='oauth/google'>
       <wiki:Param name='redirect' value='${redirect}'/></wiki:Link>">
    <svg class="oauth-icon" viewBox="0 0 24 24" width="18" height="18">
      <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/>
      <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
      <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"/>
      <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/>
    </svg>
    <fmt:message key="login.oauth.google"/>
  </a>
  </c:if>

</div>
</c:if>
```

Add CSS styles to the template's CSS file or inline:

```css
.oauth-login-options {
  margin-top: 20px;
}

.oauth-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 10px 16px;
  margin-bottom: 10px;
  border: 1px solid #ddd;
  background-color: #fff;
  color: #333;
}

.oauth-btn:hover {
  background-color: #f5f5f5;
}

.oauth-icon {
  margin-right: 10px;
}

.oauth-google {
  border-color: #4285f4;
}
```

Add to `templates/default/default_en.properties`:

```properties
login.oauth.or=Or sign in with
login.oauth.google=Continue with Google
```

#### C.2.12 Set JSP Variables

Update the beginning of `LoginContent.jsp` to set OAuth configuration variables:

```jsp
<%
    Context ctx = Context.findContext( pageContext );
    AuthenticationManager mgr = ctx.getEngine().getManager( AuthenticationManager.class );

    // OAuth configuration
    Properties wikiProps = ctx.getEngine().getWikiProperties();
    boolean oauthEnabled = Boolean.parseBoolean(
        wikiProps.getProperty("jspwiki.oauth.enabled", "false"));
    boolean googleOAuthEnabled = oauthEnabled && Boolean.parseBoolean(
        wikiProps.getProperty("jspwiki.oauth.google.enabled", "false"));

    String loginURL = "";
    // ... rest of existing code
%>
<c:set var="oauthEnabled" value="<%= oauthEnabled %>" />
<c:set var="googleOAuthEnabled" value="<%= googleOAuthEnabled %>" />
<c:set var="redirect" value="${param.redirect != null ? param.redirect : ''}" />
```

---

## Part D: Configuration Guide

### D.1 jspwiki.properties Configuration

Add these properties to your `jspwiki-custom.properties` file:

```properties
#############################################################################
# OAuth 2.0 / OpenID Connect Configuration
#############################################################################

# Master switch for OAuth authentication
jspwiki.oauth.enabled = true

# Automatically create user accounts for new OAuth users
# Set to false to require admin to pre-create accounts
jspwiki.oauth.autoCreateUsers = true

#---------------------------------------------------------------------------
# Google OAuth 2.0 / OpenID Connect
#---------------------------------------------------------------------------

# Enable Google authentication
jspwiki.oauth.google.enabled = true

# OAuth client credentials from Google Cloud Console
# SECURITY: Consider using environment variables or secrets management
jspwiki.oauth.google.clientId = YOUR_CLIENT_ID.apps.googleusercontent.com
jspwiki.oauth.google.clientSecret = GOCSPX-YOUR_CLIENT_SECRET

# OAuth scopes (space-separated)
# Default: openid email profile
jspwiki.oauth.google.scopes = openid email profile

#---------------------------------------------------------------------------
# Future: GitHub OAuth 2.0
#---------------------------------------------------------------------------

# jspwiki.oauth.github.enabled = false
# jspwiki.oauth.github.clientId =
# jspwiki.oauth.github.clientSecret =
```

### D.2 Environment Variable Configuration (Recommended for Production)

For security, use environment variables for secrets:

```properties
# In jspwiki-custom.properties
jspwiki.oauth.google.clientId = ${env:JSPWIKI_OAUTH_GOOGLE_CLIENT_ID}
jspwiki.oauth.google.clientSecret = ${env:JSPWIKI_OAUTH_GOOGLE_CLIENT_SECRET}
```

Set environment variables in your deployment:

```bash
export JSPWIKI_OAUTH_GOOGLE_CLIENT_ID="your-client-id.apps.googleusercontent.com"
export JSPWIKI_OAUTH_GOOGLE_CLIENT_SECRET="GOCSPX-your-secret"
```

### D.3 Tomcat Configuration for Environment Variables

In `catalina.properties` or `setenv.sh`:

```bash
# setenv.sh
export JSPWIKI_OAUTH_GOOGLE_CLIENT_ID="your-client-id"
export JSPWIKI_OAUTH_GOOGLE_CLIENT_SECRET="your-secret"
```

### D.4 Security Policy Updates

If using JAAS security policy, ensure OAuth classes have appropriate permissions:

```
# In jspwiki.policy
grant codeBase "file:${jspwiki.home}/WEB-INF/lib/jspwiki-main-*.jar" {
    // Allow OAuth to make HTTPS connections
    permission java.net.SocketPermission "accounts.google.com:443", "connect,resolve";
    permission java.net.SocketPermission "oauth2.googleapis.com:443", "connect,resolve";
    permission java.net.SocketPermission "www.googleapis.com:443", "connect,resolve";
    permission java.net.SocketPermission "openidconnect.googleapis.com:443", "connect,resolve";
};
```

---

## Part E: Testing Strategy

### E.1 Unit Tests

#### E.1.1 OAuthConfigurationTest.java

**Location:** `jspwiki-main/src/test/java/org/apache/wiki/auth/oauth/OAuthConfigurationTest.java`

```java
package org.apache.wiki.auth.oauth;

import org.apache.wiki.TestEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class OAuthConfigurationTest {

    private TestEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = TestEngine.getTestProperties();
        props.setProperty("jspwiki.oauth.enabled", "true");
        props.setProperty("jspwiki.oauth.google.enabled", "true");
        props.setProperty("jspwiki.oauth.google.clientId", "test-client-id");
        props.setProperty("jspwiki.oauth.google.clientSecret", "test-secret");
        engine = new TestEngine(props);
    }

    @Test
    void testOAuthEnabled() {
        OAuthConfiguration config = new OAuthConfiguration(engine);
        assertTrue(config.isOAuthEnabled());
        assertTrue(config.isGoogleEnabled());
    }

    @Test
    void testOAuthDisabled() throws Exception {
        Properties props = TestEngine.getTestProperties();
        props.setProperty("jspwiki.oauth.enabled", "false");
        TestEngine engine = new TestEngine(props);

        OAuthConfiguration config = new OAuthConfiguration(engine);
        assertFalse(config.isOAuthEnabled());
        assertFalse(config.isGoogleEnabled());
    }

    @Test
    void testValidation() {
        OAuthConfiguration config = new OAuthConfiguration(engine);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testValidationFailsMissingClientId() throws Exception {
        Properties props = TestEngine.getTestProperties();
        props.setProperty("jspwiki.oauth.enabled", "true");
        props.setProperty("jspwiki.oauth.google.enabled", "true");
        props.setProperty("jspwiki.oauth.google.clientSecret", "secret");
        // clientId not set
        TestEngine engine = new TestEngine(props);

        OAuthConfiguration config = new OAuthConfiguration(engine);
        assertThrows(IllegalStateException.class, config::validate);
    }
}
```

#### E.1.2 OAuthUserInfoTest.java

```java
package org.apache.wiki.auth.oauth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OAuthUserInfoTest {

    @Test
    void testBuilder() {
        OAuthUserInfo info = new OAuthUserInfo.Builder()
            .providerId("123456")
            .providerName("google")
            .email("john.smith@example.com")
            .fullName("John Smith")
            .emailVerified(true)
            .build();

        assertEquals("123456", info.getProviderId());
        assertEquals("google", info.getProviderName());
        assertEquals("john.smith@example.com", info.getEmail());
        assertEquals("John Smith", info.getFullName());
        assertTrue(info.isEmailVerified());
    }

    @Test
    void testGenerateLoginName() {
        OAuthUserInfo info = new OAuthUserInfo.Builder()
            .providerName("google")
            .email("john.smith@example.com")
            .build();

        assertEquals("google_john.smith", info.generateLoginName());
    }

    @Test
    void testGenerateLoginNameWithSpecialChars() {
        OAuthUserInfo info = new OAuthUserInfo.Builder()
            .providerName("google")
            .email("john+test@example.com")
            .build();

        assertEquals("google_john_test", info.generateLoginName());
    }
}
```

#### E.1.3 OAuthLoginModuleTest.java

```java
package org.apache.wiki.auth.login;

import org.apache.wiki.TestEngine;
import org.apache.wiki.auth.oauth.OAuthUserInfo;
import org.apache.wiki.auth.user.XMLUserDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;
import java.security.Principal;
import java.util.HashMap;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OAuthLoginModuleTest {

    private TestEngine engine;
    private Subject subject;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = TestEngine.getTestProperties();
        props.put(XMLUserDatabase.PROP_USERDATABASE, "target/test-classes/userdatabase.xml");
        engine = new TestEngine(props);
        subject = new Subject();
    }

    @Test
    void testNewUserLogin() throws Exception {
        OAuthUserInfo userInfo = new OAuthUserInfo.Builder()
            .providerId("oauth-123")
            .providerName("google")
            .email("newuser@example.com")
            .fullName("New User")
            .emailVerified(true)
            .build();

        OAuthCallbackHandler handler = new OAuthCallbackHandler(engine, null, userInfo);
        OAuthLoginModule module = new OAuthLoginModule();

        HashMap<String, Object> options = new HashMap<>();
        options.put(OAuthLoginModule.OPTION_AUTO_CREATE, "true");

        module.initialize(subject, handler, new HashMap<>(), options);
        assertTrue(module.login());
        assertTrue(module.commit());

        Set<Principal> principals = subject.getPrincipals();
        assertEquals(1, principals.size());
    }

    @Test
    void testUnverifiedEmailRejected() {
        OAuthUserInfo userInfo = new OAuthUserInfo.Builder()
            .providerId("oauth-123")
            .providerName("google")
            .email("unverified@example.com")
            .emailVerified(false)  // Not verified
            .build();

        OAuthCallbackHandler handler = new OAuthCallbackHandler(engine, null, userInfo);
        OAuthLoginModule module = new OAuthLoginModule();

        module.initialize(subject, handler, new HashMap<>(), new HashMap<>());

        assertThrows(javax.security.auth.login.FailedLoginException.class,
            module::login);
    }

    @Test
    void testAutoCreateDisabled() {
        OAuthUserInfo userInfo = new OAuthUserInfo.Builder()
            .providerId("oauth-new")
            .providerName("google")
            .email("nonexistent@example.com")
            .emailVerified(true)
            .build();

        OAuthCallbackHandler handler = new OAuthCallbackHandler(engine, null, userInfo);
        OAuthLoginModule module = new OAuthLoginModule();

        HashMap<String, Object> options = new HashMap<>();
        options.put(OAuthLoginModule.OPTION_AUTO_CREATE, "false");

        module.initialize(subject, handler, new HashMap<>(), options);

        assertThrows(javax.security.auth.login.FailedLoginException.class,
            module::login);
    }
}
```

### E.2 Integration Tests

#### E.2.1 OAuthFlowIntegrationTest.java

**Location:** `jspwiki-it-tests/src/test/java/org/apache/wiki/its/OAuthFlowIntegrationTest.java`

```java
package org.apache.wiki.its;

import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.Selenide;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Condition.*;
import static com.codeborne.selenide.Selenide.*;

/**
 * Integration tests for OAuth flow.
 *
 * Note: These tests require a running JSPWiki instance with OAuth configured.
 * They are disabled by default as they require real OAuth credentials.
 */
@Disabled("Requires OAuth configuration")
class OAuthFlowIntegrationTest {

    @BeforeAll
    static void setUp() {
        Configuration.baseUrl = "http://localhost:8080/JSPWiki";
        Configuration.browser = "chrome";
        Configuration.headless = true;
    }

    @Test
    void testGoogleLoginButtonVisible() {
        open("/Login.jsp");
        $(".oauth-google").shouldBe(visible);
        $(".oauth-google").shouldHave(text("Continue with Google"));
    }

    @Test
    void testOAuthStartRedirect() {
        open("/oauth/google");
        // Should redirect to Google
        Selenide.webdriver().driver().url()
            .contains("accounts.google.com");
    }

    @Test
    void testOAuthDisabledShowsError() {
        // With OAuth disabled, should return 404
        open("/oauth/google");
        $("body").shouldHave(text("OAuth is not enabled"));
    }
}
```

### E.3 Manual Testing Checklist

#### E.3.1 Pre-Flight Checks

- [ ] OAuth properties configured in `jspwiki-custom.properties`
- [ ] Redirect URIs match between Google Console and JSPWiki URL
- [ ] HTTPS configured (or localhost for development)
- [ ] Test user added to Google OAuth consent screen (if external app)

#### E.3.2 Happy Path Testing

1. **Initial Login Flow**
   - [ ] Open login page
   - [ ] Click "Continue with Google" button
   - [ ] Verify redirect to Google consent screen
   - [ ] Select Google account
   - [ ] Verify redirect back to JSPWiki
   - [ ] Verify user is logged in
   - [ ] Check user profile shows OAuth metadata

2. **Returning User**
   - [ ] Log out
   - [ ] Log in with OAuth again
   - [ ] Verify existing account is used (no duplicate)
   - [ ] Verify profile data preserved

3. **Session Persistence**
   - [ ] Log in with OAuth
   - [ ] Close browser
   - [ ] Reopen browser and navigate to wiki
   - [ ] Verify session restored (if cookie auth enabled)

#### E.3.3 Error Path Testing

1. **User Cancels OAuth**
   - [ ] Start OAuth flow
   - [ ] Cancel on Google consent screen
   - [ ] Verify graceful redirect to login page
   - [ ] Verify error message displayed

2. **Invalid State (CSRF Protection)**
   - [ ] Manually craft callback URL with wrong state
   - [ ] Verify request rejected
   - [ ] Verify error logged

3. **Expired Session**
   - [ ] Start OAuth flow
   - [ ] Wait for session timeout
   - [ ] Complete OAuth flow
   - [ ] Verify appropriate error handling

4. **Unverified Email**
   - [ ] Configure test account without verified email
   - [ ] Attempt OAuth login
   - [ ] Verify login rejected with appropriate message

#### E.3.4 Security Testing

1. **CSRF Protection**
   - [ ] Verify state parameter is validated
   - [ ] Verify state is cryptographically random
   - [ ] Verify replay attacks fail

2. **Token Validation**
   - [ ] Verify ID token signature is validated
   - [ ] Verify token issuer is checked
   - [ ] Verify token audience is checked
   - [ ] Verify token expiration is checked

3. **No Secret Exposure**
   - [ ] Verify client secret not in client-side code
   - [ ] Verify client secret not in URLs
   - [ ] Verify logs don't contain secrets

---

## Part F: Security Considerations

### F.1 HTTPS Requirement

OAuth 2.0 requires HTTPS for production deployments:

- Google requires HTTPS for all redirect URIs (except localhost)
- Tokens transmitted over HTTP can be intercepted
- Session cookies should be marked Secure

**Configuration:** Ensure your Tomcat or reverse proxy is configured for SSL/TLS.

### F.2 Secret Management

**Never commit OAuth secrets to source control.**

Best practices:
- Use environment variables
- Use secrets management (HashiCorp Vault, AWS Secrets Manager, etc.)
- Rotate secrets periodically
- Use different credentials for dev/staging/production

### F.3 CSRF Protection

The implementation includes CSRF protection via:
- Random state parameter in OAuth flow
- State validation in callback
- State cleared after use

### F.4 Token Validation

ID tokens are validated for:
- Signature (using Google's public keys from JWKS endpoint)
- Issuer (must be accounts.google.com)
- Audience (must match client ID)
- Expiration (must not be expired)

### F.5 User Verification

Only users with verified email addresses can authenticate:
- Prevents impersonation with unverified emails
- `email_verified` claim is checked

### F.6 Account Linking Security

When linking OAuth accounts to existing users:
- Matching is done by email address only
- Consider adding confirmation step for existing accounts
- Admin can manually link accounts if needed

### F.7 Session Security

- OAuth tokens are not stored in the session (only user info)
- WikiSession is used for authorization after initial auth
- Logout clears local session (consider also revoking OAuth token)

---

## Part G: Troubleshooting

### G.1 Common Issues

#### "OAuth is not enabled"

**Cause:** OAuth configuration is missing or disabled.

**Solution:**
```properties
jspwiki.oauth.enabled = true
jspwiki.oauth.google.enabled = true
```

#### "Invalid redirect URI"

**Cause:** Callback URL doesn't match Google Console configuration.

**Solution:**
1. Check your JSPWiki base URL
2. Ensure Google Console has the exact redirect URI:
   `https://your-domain.com/JSPWiki/oauth/callback`
3. Don't include trailing slashes inconsistently

#### "Security validation failed" (CSRF error)

**Cause:** State parameter mismatch.

**Possible causes:**
- Session expired during OAuth flow
- User has multiple tabs open
- Cookies disabled

**Solution:**
- Check session timeout settings
- Ensure cookies are enabled
- Increase session timeout if needed

#### "User not found and auto-registration is disabled"

**Cause:** User doesn't exist and `autoCreateUsers` is false.

**Solution:**
- Enable auto-create: `jspwiki.oauth.autoCreateUsers = true`
- Or pre-create the user account

#### "Email address not verified by provider"

**Cause:** Google account has unverified email.

**Solution:**
- Have user verify their email with Google
- This is a security feature and should not be disabled

### G.2 Debugging

Enable debug logging for OAuth:

```properties
# In log4j2.xml
<Logger name="org.apache.wiki.auth.oauth" level="DEBUG" />
<Logger name="org.apache.wiki.auth.login.OAuthLoginModule" level="DEBUG" />
```

### G.3 Support Resources

- Google OAuth Documentation: https://developers.google.com/identity/protocols/oauth2
- OpenID Connect Spec: https://openid.net/connect/
- JSPWiki Mailing List: user@jspwiki.apache.org

---

## Appendix: File Summary

| File | Location | Purpose |
|------|----------|---------|
| `OAuthConfiguration.java` | `jspwiki-main/.../auth/oauth/` | Configuration holder |
| `OAuthUserInfo.java` | `jspwiki-main/.../auth/oauth/` | User info DTO |
| `OAuthProvider.java` | `jspwiki-main/.../auth/oauth/provider/` | Provider interface |
| `GoogleOAuthProvider.java` | `jspwiki-main/.../auth/oauth/provider/` | Google implementation |
| `OAuthCallback.java` | `jspwiki-main/.../auth/login/` | JAAS callback |
| `OAuthCallbackHandler.java` | `jspwiki-main/.../auth/login/` | JAAS callback handler |
| `OAuthLoginModule.java` | `jspwiki-main/.../auth/login/` | JAAS login module |
| `OAuthStartServlet.java` | `jspwiki-main/.../auth/oauth/servlet/` | Starts OAuth flow |
| `OAuthCallbackServlet.java` | `jspwiki-main/.../auth/oauth/servlet/` | Handles callback |
| `web.xml` | `jspwiki-war/.../WEB-INF/` | Servlet registration |
| `LoginContent.jsp` | `jspwiki-war/.../templates/default/` | UI buttons |
| `pom.xml` | `jspwiki-main/` | Dependencies |
| `jspwiki-custom.properties` | Deployment | Configuration |

---

## Part H: Cloudflare Tunnel & SquareSpace DNS Setup

This section covers specific considerations for deployments using **Cloudflare Tunnel (cloudflared)** for exposing a privately-hosted server and **SquareSpace** for DNS management.

### H.1 Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Your Infrastructure with Cloudflare Tunnel               │
└─────────────────────────────────────────────────────────────────────────────┘

                                    Internet
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                           SquareSpace DNS                                    │
│  wiki.yourdomain.com  →  CNAME  →  xxxxxxxx.cfargotunnel.com                │
└──────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                         Cloudflare Edge Network                              │
│  • SSL/TLS termination (Cloudflare certificates)                            │
│  • DDoS protection                                                           │
│  • WAF (Web Application Firewall)                                           │
│  • Caching (if configured)                                                   │
└──────────────────────────────────────────────────────────────────────────────┘
                                       │
                              Cloudflare Tunnel
                            (encrypted connection)
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                        Your Private Server                                   │
│  ┌─────────────────┐                                                        │
│  │   cloudflared   │ ←── Tunnel daemon                                      │
│  │   (connector)   │                                                        │
│  └────────┬────────┘                                                        │
│           │ localhost:8080                                                   │
│           ▼                                                                  │
│  ┌─────────────────┐                                                        │
│  │     Tomcat      │                                                        │
│  │    JSPWiki      │                                                        │
│  └─────────────────┘                                                        │
└──────────────────────────────────────────────────────────────────────────────┘
```

### H.2 SquareSpace DNS Configuration

#### H.2.1 Setting Up DNS Records

1. **Log in to SquareSpace**
   - Go to your domain settings
   - Navigate to DNS Settings

2. **Add CNAME Record for Cloudflare Tunnel**
   ```
   Type:  CNAME
   Host:  wiki (or your subdomain, e.g., "kb", "docs")
   Data:  <tunnel-id>.cfargotunnel.com
   TTL:   Automatic or 1 hour
   ```

   **Example:**
   ```
   Host:  wiki
   Data:  a1b2c3d4-e5f6-7890-abcd-ef1234567890.cfargotunnel.com
   ```

3. **If Using Root Domain**

   SquareSpace doesn't support CNAME at root domain. Options:
   - Use a subdomain (recommended): `wiki.yourdomain.com`
   - Use Cloudflare as DNS provider instead of SquareSpace

4. **Verify DNS Propagation**
   ```bash
   # Check DNS resolution
   dig wiki.yourdomain.com CNAME

   # Or use nslookup
   nslookup wiki.yourdomain.com
   ```

#### H.2.2 DNS Propagation Time

- SquareSpace DNS changes typically propagate within 1-48 hours
- During initial setup, Google OAuth configuration should wait until DNS is fully propagated
- Test with: `curl -I https://wiki.yourdomain.com`

### H.3 Cloudflare Tunnel Configuration

#### H.3.1 Tunnel Configuration File

Your `~/.cloudflared/config.yml` should route traffic to Tomcat:

```yaml
tunnel: your-tunnel-id
credentials-file: /home/username/.cloudflared/your-tunnel-id.json

ingress:
  # JSPWiki application
  - hostname: wiki.yourdomain.com
    service: http://localhost:8080
    originRequest:
      # Important for OAuth: preserve original host header
      httpHostHeader: wiki.yourdomain.com
      # Don't verify local TLS (Tomcat likely uses HTTP internally)
      noTLSVerify: false

  # Catch-all (required)
  - service: http_status:404
```

#### H.3.2 Critical Settings for OAuth

1. **Preserve Host Header**

   OAuth callbacks depend on the correct `Host` header. Ensure:
   ```yaml
   originRequest:
     httpHostHeader: wiki.yourdomain.com
   ```

2. **WebSocket Support** (if needed for future features)
   ```yaml
   originRequest:
     noTLSVerify: false
     connectTimeout: 30s
   ```

#### H.3.3 Running Cloudflared as a Service

```bash
# Install as system service
sudo cloudflared service install

# Or run manually for testing
cloudflared tunnel run your-tunnel-name
```

### H.4 JSPWiki Configuration for Cloudflare Tunnel

#### H.4.1 Base URL Configuration

**Critical:** JSPWiki must know its external URL for generating correct OAuth callback URLs.

In `jspwiki-custom.properties`:

```properties
#############################################################################
# Base URL Configuration (Required for Cloudflare Tunnel)
#############################################################################

# The external URL users access (through Cloudflare)
jspwiki.baseURL = https://wiki.yourdomain.com/JSPWiki/

# Alternative: If you mount at root context
# jspwiki.baseURL = https://wiki.yourdomain.com/
```

#### H.4.2 Proxy Headers Configuration

Cloudflare adds headers that JSPWiki should recognize. In Tomcat's `server.xml`, add a RemoteIpValve:

```xml
<Valve className="org.apache.catalina.valves.RemoteIpValve"
       remoteIpHeader="CF-Connecting-IP"
       protocolHeader="X-Forwarded-Proto"
       protocolHeaderHttpsValue="https"
       trustedProxies="173\.245\.4[89]\..*|103\.21\.244\..*|103\.22\.200\..*|..."
/>
```

Or simpler, trust all proxies (only if your server is not directly accessible):

```xml
<Valve className="org.apache.catalina.valves.RemoteIpValve"
       remoteIpHeader="CF-Connecting-IP"
       protocolHeader="X-Forwarded-Proto"
       internalProxies=".*"
/>
```

#### H.4.3 OAuth Callback URL Generation Fix

The OAuth servlets build callback URLs dynamically. With Cloudflare Tunnel, the servlet sees `localhost:8080` internally but needs to generate `https://wiki.yourdomain.com/...` externally.

**Option 1: Use X-Forwarded Headers (Recommended)**

Ensure `cloudflared` passes these headers and Tomcat's RemoteIpValve processes them. The servlet code already uses `request.getScheme()` and `request.getServerName()` which will work correctly with the valve.

**Option 2: Configure Explicit Callback URL**

Add to `jspwiki-custom.properties`:

```properties
# Explicit OAuth callback URL (overrides auto-detection)
jspwiki.oauth.callbackUrl = https://wiki.yourdomain.com/JSPWiki/oauth/callback
```

Then modify `OAuthStartServlet.java` to use this property:

```java
private String getCallbackUrl(HttpServletRequest request) {
    String configuredUrl = engine.getWikiProperties()
        .getProperty("jspwiki.oauth.callbackUrl");

    if (configuredUrl != null && !configuredUrl.isEmpty()) {
        return configuredUrl;
    }

    // Fall back to dynamic URL building
    return buildCallbackUrl(request);
}
```

### H.5 Google OAuth Console Settings for Cloudflare

#### H.5.1 Authorized Redirect URIs

In Google Cloud Console → Credentials → OAuth 2.0 Client IDs:

```
# Production (Cloudflare Tunnel URL)
https://wiki.yourdomain.com/JSPWiki/oauth/callback

# Development (local testing, bypassing tunnel)
http://localhost:8080/JSPWiki/oauth/callback
```

#### H.5.2 Authorized JavaScript Origins

```
https://wiki.yourdomain.com
http://localhost:8080
```

#### H.5.3 Authorized Domains

In OAuth consent screen:

```
yourdomain.com
```

### H.6 SSL/TLS Considerations

#### H.6.1 Cloudflare SSL Modes

Cloudflare offers several SSL modes. For OAuth, use:

| Mode | Description | Recommendation |
|------|-------------|----------------|
| **Full (Strict)** | Validates origin certificate | Best security, requires cert on origin |
| **Full** | Encrypts but doesn't validate | Good if origin has self-signed cert |
| **Flexible** | HTTPS to Cloudflare, HTTP to origin | Works but less secure |

**For OAuth:** "Full" or "Full (Strict)" is recommended since OAuth tokens transit through the tunnel.

#### H.6.2 Cloudflare Dashboard Settings

1. **SSL/TLS → Overview**
   - Set encryption mode to "Full" or "Full (Strict)"

2. **SSL/TLS → Edge Certificates**
   - Ensure "Always Use HTTPS" is ON
   - Enable "Automatic HTTPS Rewrites"

3. **SSL/TLS → Origin Server** (if using Full Strict)
   - Create Origin Certificate for your server
   - Install on Tomcat (optional with tunnel, but adds security)

### H.7 Cloudflare Security Settings

#### H.7.1 WAF Rules for OAuth

Cloudflare's WAF might block OAuth callbacks. Create exceptions:

1. **Go to Security → WAF → Custom Rules**

2. **Create Exception Rule:**
   ```
   Name: Allow OAuth Callbacks

   If: URI Path equals "/JSPWiki/oauth/callback"
   Then: Skip remaining WAF rules
   ```

3. **Allow Google's Redirect Parameters:**
   - The `code` and `state` parameters in OAuth callbacks might trigger security rules
   - Monitor Firewall Events after implementation

#### H.7.2 Rate Limiting

OAuth endpoints should have reasonable rate limits:

```
URI Path: /JSPWiki/oauth/*
Rate Limit: 20 requests per minute per IP
Action: Challenge or Block
```

#### H.7.3 Bot Protection

Ensure Cloudflare's bot protection doesn't block OAuth flows:
- OAuth callbacks are legitimate automated requests from Google
- May need to allow Google's IP ranges

### H.8 Troubleshooting Cloudflare + OAuth

#### H.8.1 "Redirect URI Mismatch"

**Symptoms:** Google rejects the OAuth callback.

**Causes:**
1. URL in Google Console doesn't match what JSPWiki generates
2. Cloudflare is modifying the URL

**Diagnosis:**
```bash
# Check what URL JSPWiki sees
curl -v https://wiki.yourdomain.com/JSPWiki/oauth/google 2>&1 | grep -i location
```

**Solutions:**
- Verify `jspwiki.baseURL` is set correctly
- Check Tomcat RemoteIpValve configuration
- Use explicit `jspwiki.oauth.callbackUrl`

#### H.8.2 "Connection Refused" or Timeout

**Symptoms:** OAuth flow hangs or fails.

**Causes:**
1. `cloudflared` not running
2. Tunnel not connected
3. Tomcat not running on expected port

**Diagnosis:**
```bash
# Check tunnel status
cloudflared tunnel info your-tunnel-name

# Check if Tomcat is listening
netstat -tlnp | grep 8080

# Test direct connection
curl http://localhost:8080/JSPWiki/
```

#### H.8.3 "SSL Handshake Failed"

**Symptoms:** 525 or 526 errors from Cloudflare.

**Causes:**
1. SSL mode mismatch
2. Origin certificate issues

**Solutions:**
- Set Cloudflare SSL mode to "Full" (not "Full Strict") if no origin cert
- Or install Cloudflare Origin Certificate on Tomcat

#### H.8.4 OAuth State Mismatch with Cloudflare Caching

**Symptoms:** "Security validation failed" errors intermittently.

**Cause:** Cloudflare might cache pages that shouldn't be cached.

**Solutions:**

1. **Page Rules:**
   ```
   URL: wiki.yourdomain.com/JSPWiki/oauth/*
   Setting: Cache Level = Bypass
   ```

2. **Or Cache Rules:**
   ```
   If: URI Path starts with "/JSPWiki/oauth"
   Then: Bypass cache
   ```

#### H.8.5 Real IP Address Logging

**Issue:** JSPWiki logs show Cloudflare IPs instead of real user IPs.

**Solution:** Configure Tomcat to use `CF-Connecting-IP` header (see H.4.2).

Verify in JSPWiki logs:
```
# Should show real user IP, not Cloudflare edge IP
grep "login" jspwiki.log | head -5
```

### H.9 Complete Configuration Checklist

#### H.9.1 SquareSpace DNS

- [ ] CNAME record created: `wiki` → `<tunnel-id>.cfargotunnel.com`
- [ ] DNS propagation verified: `dig wiki.yourdomain.com`

#### H.9.2 Cloudflare Tunnel

- [ ] Tunnel created and credentials file present
- [ ] `config.yml` routes `wiki.yourdomain.com` → `localhost:8080`
- [ ] `httpHostHeader` configured to preserve hostname
- [ ] `cloudflared` running as service or in background

#### H.9.3 Cloudflare Dashboard

- [ ] SSL mode set to "Full" or "Full (Strict)"
- [ ] "Always Use HTTPS" enabled
- [ ] WAF exception for `/JSPWiki/oauth/*` (if needed)
- [ ] Cache bypass for OAuth paths

#### H.9.4 Tomcat

- [ ] RemoteIpValve configured for `CF-Connecting-IP` and `X-Forwarded-Proto`
- [ ] Listening on port matching tunnel config

#### H.9.5 JSPWiki

- [ ] `jspwiki.baseURL` set to `https://wiki.yourdomain.com/JSPWiki/`
- [ ] OAuth properties configured
- [ ] (Optional) `jspwiki.oauth.callbackUrl` explicitly set

#### H.9.6 Google Cloud Console

- [ ] Redirect URI: `https://wiki.yourdomain.com/JSPWiki/oauth/callback`
- [ ] Authorized domain: `yourdomain.com`
- [ ] JavaScript origin: `https://wiki.yourdomain.com`

### H.10 Testing Your Setup

#### H.10.1 Pre-OAuth Tests

```bash
# 1. Verify DNS
dig wiki.yourdomain.com CNAME

# 2. Verify tunnel connectivity
curl -I https://wiki.yourdomain.com/JSPWiki/

# 3. Verify headers are passed correctly
curl -I https://wiki.yourdomain.com/JSPWiki/Wiki.jsp 2>&1 | grep -i "x-forwarded"

# 4. Verify JSPWiki sees correct URL
# Check the page source for any generated URLs
curl -s https://wiki.yourdomain.com/JSPWiki/Wiki.jsp | grep -o 'href="[^"]*"' | head -10
```

#### H.10.2 OAuth Flow Test

1. Open browser developer tools (Network tab)
2. Navigate to login page
3. Click "Continue with Google"
4. Observe:
   - Redirect to `accounts.google.com` with correct `redirect_uri`
   - After Google auth, redirect back to your tunnel URL
   - No mixed content warnings
   - Final authenticated state

---

## Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2024 | Claude | Initial comprehensive plan |

---

*This document was generated to provide a complete implementation guide for OAuth SSO in JSPWiki.*
