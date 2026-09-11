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
import org.fao.geonet.api.exception.ResourceNotFoundException;
import org.fao.geonet.domain.MetadataResource;
import org.fao.geonet.domain.MetadataResourceVisibility;
import org.fao.geonet.domain.Profile;
import org.fao.geonet.events.history.AttachmentAddedEvent;
import org.fao.geonet.utils.Log;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.net.URL;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
        CORE_POOL_SIZE, MAX_POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY), new NamedThreadFactory("resource-upload-"));

    /**
     * Schedules an asynchronous resource upload.
     * The caller must have verified edit rights on the record.
     *
     * @return the registered task, marked failed if the executor rejects it
     */
    public synchronized ResourceUploadTask submit(
        Store store,
        ServiceContext requestContext,
        String metadataUuid,
        URL url,
        MetadataResourceVisibility visibility,
        Boolean approved
    ) {
        UserSession userSession = requestContext.getUserSession();
        String language = requestContext.getLanguage();
        Integer ownerUserId =
            userSession != null ? userSession.getUserIdAsInt() : null;

        SecurityContext securityContext = SecurityContextHolder.getContext();

        ResourceUploadTask task = new ResourceUploadTask(
            metadataUuid,
            url.toString(),
            visibility,
            approved,
            ownerUserId);

        FutureTask<Void> future = new FutureTask<>(() -> {
            run(
                store,
                task,
                url,
                userSession,
                securityContext,
                language,
                metadataUuid,
                visibility,
                approved);
            return null;
        });

        // Attach the execution handle before making the task discoverable.
        task.setFuture(future);
        registry.register(task);

        try {
            executor.execute(future);
        } catch (RejectedExecutionException e) {
            task.fail(
                "The server is too busy to process this upload right now. "
                    + "Please retry later.");

            future.cancel(false);
        }

        return task;
    }

    private void run(Store store, ResourceUploadTask task, URL url, UserSession userSession, SecurityContext securityContext, String language,
                     String metadataUuid, MetadataResourceVisibility visibility, Boolean approved) {
        SecurityContextHolder.setContext(securityContext);
        ConfigurableApplicationContext appContext = ApplicationContextHolder.get();

        try {
            if (!task.start()) {
                if (Log.isDebugEnabled(org.fao.geonet.constants.Geonet.RESOURCES)) {
                    Log.debug(org.fao.geonet.constants.Geonet.RESOURCES,
                        "Skipping async upload task " + task.getId() + " because it did not start. status=" + task.getStatus());
                }
                return;
            }

            ServiceContext context = serviceManager.createServiceContext("attachmentAsyncUpload", appContext);
            context.setLanguage(language == null ? "eng" : language);
            context.setUserSession(userSession);
            context.setAsThreadLocal();

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

                    if (!task.startFinalizing()) {
                        throw new CancellationException("Resource upload task " + task.getId() + " could not enter finalizing state.");
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
            task.markCancelledAfterCleanup();
            if (Log.isDebugEnabled(org.fao.geonet.constants.Geonet.RESOURCES)) {
                Log.debug(org.fao.geonet.constants.Geonet.RESOURCES,
                    "Finished async upload task " + task.getId() + ". status=" + task.getStatus());
            }
            SecurityContextHolder.clearContext();
        }
    }

    public synchronized boolean cancel(ResourceUploadTask task) {
        if (!task.cancel()) {
            return false;
        }

        FutureTask<Void> future = task.getFuture();

        future.cancel(true);
        executor.remove(future);

        return true;
    }

    public List<ResourceUploadTask> listUploadsForUser(String metadataUuid, UserSession userSession) {
        return registry.getByMetadataUuid(metadataUuid).stream()
            .filter(t -> isTaskOwnerOrAdmin(t, userSession))
            .collect(Collectors.toList());
    }

    /**
     * Get an upload task by ID and verify that it belongs to the given metadata UUID and is owned by the current user (or admin).
     *
     * @param metadataUuid the metadata UUID
     * @param taskId       the upload task ID
     * @param request      the HTTP request (used to get the user session)
     * @return the upload task if found and owned by the user
     * @throws ResourceNotFoundException if the task is not found or does not belong to the metadata UUID
     * @throws SecurityException         if the user does not own the task and is not an admin
     */
    public ResourceUploadTask getOwnedTaskOrThrow(String metadataUuid, String taskId, HttpServletRequest request) throws Exception {
        ResourceUploadTask task = registry == null ? null : registry.get(taskId);
        if (task == null || !task.getMetadataUuid().equals(metadataUuid)) {
            throw new ResourceNotFoundException(String.format("Upload task '%s' not found for record '%s'.", taskId, metadataUuid));
        }
        UserSession userSession = ApiUtils.getUserSession(request.getSession());
        if (!isTaskOwnerOrAdmin(task, userSession)) {
            throw new SecurityException(String.format("User '%s' is not allowed to access upload task '%s'.",
                userSession.getUsername(), taskId));
        }
        return task;
    }

    /**
     * Check if the given user session is the owner of the task or has admin privileges.
     *
     * @param task        the upload task
     * @param userSession the user session
     * @return true if the user is the owner or an admin, false otherwise
     */
    private boolean isTaskOwnerOrAdmin(ResourceUploadTask task, UserSession userSession) {
        if (userSession == null) {
            return false;
        }
        if (userSession.getProfile() != null && userSession.getProfile().equals(Profile.Administrator)) {
            return true;
        }
        return task.getOwnerUserId() != null && task.getOwnerUserId().equals(userSession.getUserIdAsInt());
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
