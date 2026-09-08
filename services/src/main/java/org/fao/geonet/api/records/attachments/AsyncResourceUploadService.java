/*
 * =============================================================================
 * ===	Copyright (C) 2001-2026 Food and Agriculture Organization of the
 * ===	United Nations (FAO-UN), United Nations World Food Programme (WFP)
 * ===	and United Nations Environment Programme (UNEP)
 * ===
 * ===	This program is free software; you can redistribute it and/or modify
 * ===	it under the terms of the GNU General Public License as published by
 * ===	the Free Software Foundation; either version 2 of the License, or (at
 * ===	your option) any later version.
 * ===
 * ===	This program is distributed in the hope that it will be useful, but
 * ===	WITHOUT ANY WARRANTY; without even the implied warranty of
 * ===	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * ===	General Public License for more details.
 * ===
 * ===	You should have received a copy of the GNU General Public License
 * ===	along with this program; if not, write to the Free Software
 * ===	Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
 * ===
 * ===	Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
 * ===	Rome - Italy. email: geonetwork@osgeo.org
 * ==============================================================================
 */
package org.fao.geonet.api.records.attachments;

import jeeves.server.UserSession;
import jeeves.server.context.ServiceContext;
import jeeves.server.dispatchers.ServiceManager;
import jeeves.transaction.TransactionManager;
import org.fao.geonet.ApplicationContextHolder;
import org.fao.geonet.api.ApiUtils;
import org.fao.geonet.domain.MetadataResource;
import org.fao.geonet.domain.MetadataResourceVisibility;
import org.fao.geonet.events.history.AttachmentAddedEvent;
import org.fao.geonet.utils.Log;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.http.ContentDisposition;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs "upload a resource from a URL" requests
 * ({@code PUT .../attachments?url=...&async=true}) in the background so the
 * originating HTTP request can return immediately with a {@link ResourceUploadTask}
 * that the caller polls for progress/completion, instead of blocking until the
 * whole remote file has been downloaded and stored.
 *
 * <p>Uses a small dedicated, bounded thread pool (deliberately <em>not</em> the
 * shared {@link org.fao.geonet.util.ThreadPool} bean, whose
 * {@code CallerRunsPolicy} would run rejected tasks on the calling/request
 * thread and defeat the purpose of going asynchronous). When the pool is
 * saturated, submission is rejected and the caller is told to retry later.
 */
@Service
public class AsyncResourceUploadService implements DisposableBean {

    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 8;
    private static final int QUEUE_CAPACITY = 50;
    private static final long KEEP_ALIVE_SECONDS = 60;

    @Autowired
    private ResourceUploadTaskRegistry registry;

    @Autowired
    private ServiceManager serviceManager;

    private final ConcurrentMap<String, Future<?>> futures = new ConcurrentHashMap<>();

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
        CORE_POOL_SIZE, MAX_POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY), new NamedThreadFactory("resource-upload-"));

    /**
     * Schedule an asynchronous download of {@code url} into the store for
     * {@code metadataUuid}. The caller is responsible for having already
     * verified edit rights on the record.
     *
     * @return the newly created, registered task (initially {@code PENDING})
     * @throws RejectedExecutionException if the background pool is saturated
     */
    public ResourceUploadTask submit(Store store, ServiceContext requestContext, String metadataUuid, URL url,
                                      MetadataResourceVisibility visibility, Boolean approved) {
        UserSession userSession = requestContext.getUserSession();
        String language = requestContext.getLanguage();
        Integer ownerUserId = userSession != null ? userSession.getUserIdAsInt() : null;

        SecurityContext securityContext = SecurityContextHolder.getContext();

        ResourceUploadTask task = new ResourceUploadTask(metadataUuid, url.toString(), visibility, approved, ownerUserId);
        // Get the real filename from remote server's HTTP headers before registering
        String filename = getFilenameFromUrlHeaders(url);
        if (filename != null) {
            task.setFilename(filename);
        }
        registry.register(task);
        if (Log.isDebugEnabled(org.fao.geonet.constants.Geonet.RESOURCES)) {
            Log.debug(org.fao.geonet.constants.Geonet.RESOURCES,
                "Registered async upload task " + task.getId() + " for metadata '" + metadataUuid + "' from URL '" + url + "'.");
        }

        try {
//            executor.execute(() -> run(store, task, url, userSession, securityContext, language, metadataUuid, visibility, approved));
            Future<?> future = executor.submit(() -> run(store, task, url, userSession, securityContext, language, metadataUuid, visibility, approved));
            futures.put(task.getId(), future);
            if (Log.isDebugEnabled(org.fao.geonet.constants.Geonet.RESOURCES)) {
                Log.debug(org.fao.geonet.constants.Geonet.RESOURCES,
                    "Submitted async upload task " + task.getId() + " to executor. queuedTasks=" + executor.getQueue().size());
            }
        } catch (RejectedExecutionException e) {
            task.fail("The server is too busy to process this upload right now. Please retry later.");
        }
        return task;
    }

    private void run(Store store, ResourceUploadTask task, URL url, UserSession userSession, SecurityContext securityContext, String language,
                      String metadataUuid, MetadataResourceVisibility visibility, Boolean approved) {
        SecurityContextHolder.setContext(securityContext);

        task.start();
        ConfigurableApplicationContext appContext = ApplicationContextHolder.get();

        ServiceContext context = serviceManager.createServiceContext("attachmentAsyncUpload", appContext);
        context.setLanguage(language == null ? "eng" : language);
        context.setUserSession(userSession);
        context.setAsThreadLocal();

        try {
            if (Log.isDebugEnabled(org.fao.geonet.constants.Geonet.RESOURCES)) {
                Log.debug(org.fao.geonet.constants.Geonet.RESOURCES,
                    "Starting async upload task " + task.getId() + " for metadata '" + metadataUuid + "' from URL '" + url + "'.");
            }
            MetadataResource resource = TransactionManager.runInTransaction(
                "AsyncResourceUpload-" + task.getId(), appContext,
                TransactionManager.TransactionRequirement.CREATE_NEW,
                TransactionManager.CommitBehavior.ALWAYS_COMMIT,
                false,
                status -> {
                    MetadataResource uploaded = store.putResource(context, metadataUuid, url, visibility, approved, task);

                    if (task.isCancelled()) {
                        throw new CancellationException("Resource upload task " + task.getId() + " was cancelled.");
                    }

                    String metadataIdString = ApiUtils.getInternalId(metadataUuid, approved);
                    if (metadataIdString != null) {
                        long metadataId = Long.parseLong(metadataIdString);
                        Integer userId = task.getOwnerUserId();
                        new AttachmentAddedEvent(metadataId, userId, uploaded.getFilename()).publish(appContext);
                    }
                    return uploaded;
                });
            // Update task with actual stored filename (in case server-stored name differs from Content-Disposition)
            task.setFilename(resource.getFilename());
            task.complete(resource);
            if (Log.isDebugEnabled(org.fao.geonet.constants.Geonet.RESOURCES)) {
                Log.debug(org.fao.geonet.constants.Geonet.RESOURCES,
                    "Completed async upload task " + task.getId() + " with stored file '" + resource.getFilename() +
                        "' (" + resource.getSize() + " bytes).");
            }
        } catch (Exception e) {
            if (!task.isCancelled()) {
                Log.error(org.fao.geonet.constants.Geonet.RESOURCES,
                    "Error uploading resource from URL '" + url + "' for record '" + metadataUuid + "' (task " + task.getId() + ")", e);
                task.fail(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        } finally {
            futures.remove(task.getId());
            if (Log.isDebugEnabled(org.fao.geonet.constants.Geonet.RESOURCES)) {
                Log.debug(org.fao.geonet.constants.Geonet.RESOURCES,
                    "Finished async upload task " + task.getId() + ". status=" + task.getStatus());
            }
            SecurityContextHolder.clearContext();
        }
    }

    public boolean cancel(ResourceUploadTask task) {
        if (!task.cancel()) {
            return false;
        }

        Future<?> future = futures.remove(task.getId());
        if (future != null) {
            future.cancel(true);
        }

        return true;
    }

    /**
     * Get the filename from the remote URL's Content-Disposition header via HEAD request.
     * Falls back to extracting from URL path if the header is not available or HEAD fails.
     *
     * @return the filename, or null if it cannot be determined
     */
    private String getFilenameFromUrlHeaders(URL url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setInstanceFollowRedirects(true);

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String contentDisposition = connection.getHeaderField(HttpHeaders.CONTENT_DISPOSITION);
                if (contentDisposition != null && !contentDisposition.isEmpty()) {
                    String filename = ContentDisposition.parse(contentDisposition).getFilename();
                    if (filename != null && !filename.isEmpty()) {
                        return filename;
                    }
                }
            }
        } catch (Exception e) {
            // HEAD request failed, will fall back to URL path
        }

        // Fall back to extracting filename from URL path
        return extractFilenameFromUrl(url);
    }

    /**
     * Extract a filename from a URL path. Returns the last path segment, or null if empty.
     */
    private String extractFilenameFromUrl(URL url) {
        String path = url.getPath();
        if (path == null || path.isEmpty()) {
            return null;
        }

        String filename = path.substring(path.lastIndexOf('/') + 1);

        // Remove query string
        int queryIndex = filename.indexOf('?');
        if (queryIndex > 0) {
            filename = filename.substring(0, queryIndex);
        }

        return filename.isEmpty() ? null : filename;
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
