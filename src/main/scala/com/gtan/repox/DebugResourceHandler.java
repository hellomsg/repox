/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.gtan.repox;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import io.undertow.UndertowLogger;
import io.undertow.io.IoCallback;
import io.undertow.predicate.Predicate;
import io.undertow.predicate.Predicates;
import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.builder.HandlerBuilder;
import io.undertow.server.handlers.cache.ResponseCache;
import io.undertow.server.handlers.encoding.ContentEncodedResource;
import io.undertow.server.handlers.encoding.ContentEncodedResourceManager;
import io.undertow.server.handlers.resource.DirectoryUtils;
import io.undertow.server.handlers.resource.FileResourceManager;
import io.undertow.server.handlers.resource.Resource;
import io.undertow.server.handlers.resource.ResourceManager;
import io.undertow.util.CanonicalPathUtils;
import io.undertow.util.DateUtils;
import io.undertow.util.ETag;
import io.undertow.util.ETagUtils;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.MimeMappings;
import io.undertow.util.RedirectBuilder;
import io.undertow.util.StatusCodes;

/**
 * @author Stuart Douglas
 */
public class DebugResourceHandler implements HttpHandler {

    private final List<String> welcomeFiles = new CopyOnWriteArrayList<>(new String[]{"index.html", "index.htm", "default.html", "default.htm"});
    /**
     * If directory listing is enabled.
     */
    private volatile boolean directoryListingEnabled = false;

    /**
     * If the canonical version of paths should be passed into the resource manager.
     */
    private volatile boolean canonicalizePaths = true;

    /**
     * The mime mappings that are used to determine the content type.
     */
    private volatile MimeMappings mimeMappings = MimeMappings.DEFAULT;
    private volatile Predicate cachable = Predicates.truePredicate();
    private volatile Predicate allowed = Predicates.truePredicate();
    private volatile ResourceManager resourceManager;
    /**
     * If this is set this will be the maximum time the client will cache the resource.
     * <p/>
     * Note: Do not set this for private resources, as it will cause a Cache-Control: public
     * to be sent.
     * <p/>
     * TODO: make this more flexible
     * <p/>
     * This will only be used if the {@link #cachable} predicate returns true
     */
    private volatile Integer cacheTime;
    /**
     * we do not calculate a new expiry date every request. Instead calculate it once
     * and cache it until it is in the past.
     * <p/>
     * TODO: do we need this policy to be pluggable
     */
    private volatile long lastExpiryDate;
    private volatile String lastExpiryHeader;

    private volatile ContentEncodedResourceManager contentEncodedResourceManager;

    public DebugResourceHandler(ResourceManager resourceManager) {
        this.resourceManager = resourceManager;
    }


    @Override
    public void handleRequest(final HttpServerExchange exchange) throws Exception {
        System.out.println("Checkpoint 0");
        if (exchange.getRequestMethod().equals(Methods.GET) ||
                exchange.getRequestMethod().equals(Methods.POST)) {
            serveResource(exchange, true);
        } else if (exchange.getRequestMethod().equals(Methods.HEAD)) {
            serveResource(exchange, false);
        } else {
            exchange.setResponseCode(405);
            exchange.endExchange();
        }
    }

    private void serveResource(final HttpServerExchange exchange, final boolean sendContent) {
        System.out.println("Checkpoint 1");
        if (DirectoryUtils.sendRequestedBlobs(exchange)) {
            return;
        }

        System.out.println("Checkpoint 2");
        if (!allowed.resolve(exchange)) {
            exchange.setResponseCode(403);
            exchange.endExchange();
            return;
        }
        System.out.println("Checkpoint 3");

        ResponseCache cache = exchange.getAttachment(ResponseCache.ATTACHMENT_KEY);
        final boolean cachable = this.cachable.resolve(exchange);

        //we set caching headers before we try and serve from the cache
        if (cachable && cacheTime != null) {
            exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "public, max-age=" + cacheTime);
            if (System.currentTimeMillis() > lastExpiryDate) {
                long date = System.currentTimeMillis();
                lastExpiryHeader = DateUtils.toDateString(new Date(date));
                lastExpiryDate = date;
            }
            exchange.getResponseHeaders().put(Headers.EXPIRES, lastExpiryHeader);
        }

        if (cache != null && cachable) {
            if (cache.tryServeResponse()) {
                return;
            }
        }

        System.out.println("Checkpoint 4");

        //we now dispatch to a worker thread
        //as resource manager methods are potentially blocking
        exchange.dispatch(new Runnable() {
            @Override
            public void run() {
                Resource resource = null;
                System.out.println("Checkpoint 5");
                try {
                    if(File.separatorChar == '/' || !exchange.getRelativePath().contains(File.separator)) {
                        //we don't process resources that contain the sperator character if this is not /
                        //this prevents attacks where people use windows path seperators in file URLS's
                        resource = resourceManager.getResource(canonicalize(exchange.getRelativePath()));
                    }
                } catch (IOException e) {
                    System.out.println("Checkpoint 6");
                    UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
                    exchange.setResponseCode(500);
                    exchange.endExchange();
                    return;
                }
                if (resource == null) {
                    exchange.setResponseCode(404);
                    exchange.endExchange();
                    return;
                }
                System.out.println("Checkpoint 7");

                if (resource.isDirectory()) {
                    Resource indexResource = null;
                    try {
                        indexResource = getIndexFiles(resourceManager, resource.getPath(), welcomeFiles);
                    } catch (IOException e) {
                        UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
                        exchange.setResponseCode(500);
                        exchange.endExchange();
                        return;
                    }
                    if (indexResource == null) {
                        if (directoryListingEnabled) {
                            DirectoryUtils.renderDirectoryListing(exchange, resource);
                            return;
                        } else {
                            exchange.setResponseCode(StatusCodes.FORBIDDEN);
                            exchange.endExchange();
                            return;
                        }
                    } else if (!exchange.getRequestPath().endsWith("/")) {
                        exchange.setResponseCode(302);
                        exchange.getResponseHeaders().put(Headers.LOCATION, RedirectBuilder.redirect(exchange, exchange.getRelativePath() + "/", true));
                        exchange.endExchange();
                        return;
                    }
                    resource = indexResource;
                }

                final ETag etag = resource.getETag();
                final Date lastModified = resource.getLastModified();
                if (!ETagUtils.handleIfMatch(exchange, etag, false) ||
                        !DateUtils.handleIfUnmodifiedSince(exchange, lastModified)) {
                    exchange.setResponseCode(412);
                    exchange.endExchange();
                    return;
                }
                if (!ETagUtils.handleIfNoneMatch(exchange, etag, true) ||
                        !DateUtils.handleIfModifiedSince(exchange, lastModified)) {
                    exchange.setResponseCode(304);
                    exchange.endExchange();
                    return;
                }
                System.out.println("Checkpoint 8");
                //todo: handle range requests
                //we are going to proceed. Set the appropriate headers
                final String contentType = resource.getContentType(mimeMappings);

                if(!exchange.getResponseHeaders().contains(Headers.CONTENT_TYPE)) {
                    if (contentType != null) {
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
                    } else {
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/octet-stream");
                    }
                }
                if (lastModified != null) {
                    exchange.getResponseHeaders().put(Headers.LAST_MODIFIED, resource.getLastModifiedString());
                }
                if (etag != null) {
                    exchange.getResponseHeaders().put(Headers.ETAG, etag.toString());
                }
                Long contentLength = resource.getContentLength();
                if (contentLength != null) {
                    exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, contentLength.toString());
                }
                System.out.println("Checkpoint 9");

                final ContentEncodedResourceManager contentEncodedResourceManager = DebugResourceHandler.this.contentEncodedResourceManager;
                if (contentEncodedResourceManager != null) {
                    try {
                        ContentEncodedResource encoded = contentEncodedResourceManager.getResource(resource, exchange);
                        if (encoded != null) {
                            exchange.getResponseHeaders().put(Headers.CONTENT_ENCODING, encoded.getContentEncoding());
                            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, encoded.getResource().getContentLength());
                            encoded.getResource().serve(exchange.getResponseSender(), exchange, IoCallback.END_EXCHANGE);
                            return;
                        }

                    } catch (IOException e) {
                        //TODO: should this be fatal
                        UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
                        exchange.setResponseCode(500);
                        exchange.endExchange();
                        return;
                    }
                }
                System.out.println("Checkpoint 10");

                if (!sendContent) {
                    System.out.println("Checkpoint 11");
                    exchange.endExchange();
                } else {
//                    resource.serve(exchange.getResponseSender(), exchange, IoCallback.END_EXCHANGE);
                    System.out.println("Checkpoint 12");
                    resource.serve(exchange.getResponseSender(), exchange, new DebugIoCallback());
                }
            }
        });
    }

    private Resource getIndexFiles(ResourceManager resourceManager, final String base, List<String> possible) throws IOException {
        String realBase;
        if (base.endsWith("/")) {
            realBase = base;
        } else {
            realBase = base + "/";
        }
        for (String possibility : possible) {
            Resource index = resourceManager.getResource(canonicalize(realBase + possibility));
            if (index != null) {
                return index;
            }
        }
        return null;
    }

    private String canonicalize(String s) {
        if(canonicalizePaths) {
            return CanonicalPathUtils.canonicalize(s);
        }
        return s;
    }

    public boolean isDirectoryListingEnabled() {
        return directoryListingEnabled;
    }

    public DebugResourceHandler setDirectoryListingEnabled(final boolean directoryListingEnabled) {
        this.directoryListingEnabled = directoryListingEnabled;
        return this;
    }

    public DebugResourceHandler addWelcomeFiles(String... files) {
        this.welcomeFiles.addAll(Arrays.asList(files));
        return this;
    }

    public DebugResourceHandler setWelcomeFiles(String... files) {
        this.welcomeFiles.clear();
        this.welcomeFiles.addAll(Arrays.asList(files));
        return this;
    }

    public MimeMappings getMimeMappings() {
        return mimeMappings;
    }

    public DebugResourceHandler setMimeMappings(final MimeMappings mimeMappings) {
        this.mimeMappings = mimeMappings;
        return this;
    }

    public Predicate getCachable() {
        return cachable;
    }

    public DebugResourceHandler setCachable(final Predicate cachable) {
        this.cachable = cachable;
        return this;
    }

    public Predicate getAllowed() {
        return allowed;
    }

    public DebugResourceHandler setAllowed(final Predicate allowed) {
        this.allowed = allowed;
        return this;
    }

    public ResourceManager getResourceManager() {
        return resourceManager;
    }

    public DebugResourceHandler setResourceManager(final ResourceManager resourceManager) {
        this.resourceManager = resourceManager;
        return this;
    }

    public Integer getCacheTime() {
        return cacheTime;
    }

    public DebugResourceHandler setCacheTime(final Integer cacheTime) {
        this.cacheTime = cacheTime;
        return this;
    }

    public ContentEncodedResourceManager getContentEncodedResourceManager() {
        return contentEncodedResourceManager;
    }

    public DebugResourceHandler setContentEncodedResourceManager(ContentEncodedResourceManager contentEncodedResourceManager) {
        this.contentEncodedResourceManager = contentEncodedResourceManager;
        return this;
    }

    public boolean isCanonicalizePaths() {
        return canonicalizePaths;
    }

    /**
     * If this handler should use canonicalized paths.
     *
     * WARNING: If this is not true and {@link io.undertow.server.handlers.CanonicalPathHandler} is not installed in
     * the handler chain then is may be possible to perform a directory traversal attack. If you set this to false make
     * sure you have some kind of check in place to control the path.
     * @param canonicalizePaths If paths should be canonicalized
     */
    public void setCanonicalizePaths(boolean canonicalizePaths) {
        this.canonicalizePaths = canonicalizePaths;
    }

    public static class Builder implements HandlerBuilder {

        @Override
        public String name() {
            return "resource";
        }

        @Override
        public Map<String, Class<?>> parameters() {
            Map<String, Class<?>> params = new HashMap<>();
            params.put("location", String.class);
            params.put("allow-listing", boolean.class);
            return params;
        }

        @Override
        public Set<String> requiredParameters() {
            return Collections.singleton("location");
        }

        @Override
        public String defaultParameter() {
            return "location";
        }

        @Override
        public HandlerWrapper build(Map<String, Object> config) {
            return new Wrapper((String)config.get("location"), (Boolean) config.get("allow-listing"));
        }

    }

    private static class Wrapper implements HandlerWrapper {

        private final String location;
        private final boolean allowDirectoryListing;

        private Wrapper(String location, boolean allowDirectoryListing) {
            this.location = location;
            this.allowDirectoryListing = allowDirectoryListing;
        }

        @Override
        public HttpHandler wrap(HttpHandler handler) {
            ResourceManager rm = new FileResourceManager(new File(location), 1024);
            DebugResourceHandler DebugResourceHandler = new DebugResourceHandler(rm);
            DebugResourceHandler.setDirectoryListingEnabled(allowDirectoryListing);
            return DebugResourceHandler;
        }
    }
}