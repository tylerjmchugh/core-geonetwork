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

import org.fao.geonet.api.exception.ResourceAlreadyExistException;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.utils.Log;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * In-memory registry of {@link ResourceUploadTask}s created by asynchronous
 * "upload a resource from a URL" requests. This allows a client to poll for
 * the progress/outcome of an upload instead of keeping the originating HTTP
 * request open.
 *
 * <p>Tasks are node-local (not persisted, not shared across a cluster) and are
 * removed automatically a while after they reach a terminal state
 * ({@code COMPLETED}/{@code FAILED}), or evicted early if the registry grows
 * beyond {@link #maxTasks}.
 */
@Component
public class ResourceUploadTaskRegistry implements DisposableBean {

    /** How long a terminal (completed/failed) task is kept available for polling. */
    static final long TERMINAL_TASK_TTL_MINUTES = 30;
    /** How often the sweep for expired terminal tasks runs. */
    private static final long SWEEP_INTERVAL_MINUTES = 5;
    /** Hard cap on the number of tasks retained at once, to bound memory use. */
    private static final int MAX_TASKS = 500;

    private final Map<String, ResourceUploadTask> tasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "resource-upload-task-sweeper");
        t.setDaemon(true);
        return t;
    });

    private final long terminalTaskTtlMillis;
    private final int maxTasks;

    public ResourceUploadTaskRegistry() {
        this(TimeUnit.MINUTES.toMillis(TERMINAL_TASK_TTL_MINUTES), MAX_TASKS);
    }

    ResourceUploadTaskRegistry(long terminalTaskTtlMillis, int maxTasks) {
        this.terminalTaskTtlMillis = terminalTaskTtlMillis;
        this.maxTasks = maxTasks;
        sweeper.scheduleWithFixedDelay(this::sweep, SWEEP_INTERVAL_MINUTES, SWEEP_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    public void register(ResourceUploadTask task) {
        tasks.put(task.getId(), task);
        if (tasks.size() > maxTasks) {
            evictOldestTerminal();
        }
    }

    public ResourceUploadTask get(String taskId) {
        return tasks.get(taskId);
    }

    public List<ResourceUploadTask> getByMetadataUuid(String metadataUuid) {
        return tasks.values().stream()
            .filter(t -> t.getMetadataUuid().equals(metadataUuid))
            .sorted(Comparator.comparing(ResourceUploadTask::getSubmittedDateTime).reversed())
            .collect(Collectors.toList());
    }

    private void sweep() {
        try {
            long now = System.currentTimeMillis();
            tasks.values().removeIf(t -> t.isTerminal() && t.getEndedDateTime() != null
                && (now - t.getEndedDateTime().getTime()) > terminalTaskTtlMillis);
        } catch (Exception e) {
            Log.warning(Geonet.RESOURCES, "Error while sweeping expired resource upload tasks: " + e.getMessage());
        }
    }

    private void evictOldestTerminal() {
        tasks.values().stream()
            .filter(ResourceUploadTask::isTerminal)
            .min(Comparator.comparing(ResourceUploadTask::getEndedDateTime))
            .ifPresent(t -> tasks.remove(t.getId()));
    }

    /**
     * Resolves the filename for a resource upload and checks for duplicates.
     *
     * @param metadataUuid     The UUID of the metadata record associated with the upload.
     * @param filename         The resolved filename of the uploaded resource.
     * @param progressListener The listener to report progress and check for duplicates.
     * @throws ResourceAlreadyExistException If a duplicate upload is detected for the same filename and metadata UUID.
     */
    public synchronized void resolveFilenameAndCheck(
        String metadataUuid,
        String filename,
        ResourceUploadProgressListener progressListener)
        throws ResourceAlreadyExistException {

        progressListener.onFilenameResolved(filename);

        boolean duplicate = getByMetadataUuid(metadataUuid).stream()
            .filter(task -> task != progressListener)
            .filter(task -> !task.isTerminal())
            .anyMatch(task -> filename.equals(task.getFilename()));

        if (duplicate) {
            throw new ResourceAlreadyExistException(String.format(
                "An upload for filename '%s' is already in progress for record '%s'. "
                    + "Wait for completion before retrying.",
                filename, metadataUuid
            ));
        }
    }

    @Override
    public void destroy() {
        sweeper.shutdownNow();
    }
}
