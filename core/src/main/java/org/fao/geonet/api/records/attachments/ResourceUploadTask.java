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

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.fao.geonet.domain.MetadataResource;
import org.fao.geonet.domain.MetadataResourceVisibility;

import java.io.Closeable;
import java.io.IOException;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks the state of an asynchronous "upload a resource from a URL" request
 * ({@code PUT .../attachments?url=...&async=true}). Instances are kept in an
 * in-memory {@link ResourceUploadTaskRegistry} so a client can poll for
 * progress instead of keeping the originating HTTP request open until the
 * remote file has been fully downloaded and stored.
 *
 * <p>Task state is node-local and not persisted: it is lost on restart and, in
 * a clustered deployment, only visible on the node that accepted the request.
 */
@JsonPropertyOrder(alphabetic = true)
public class ResourceUploadTask implements ResourceUploadProgressListener {

    public enum Status {
        PENDING,
        UPLOADING,
        FINALIZING,
        CANCELLING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private static final ExecutorService STREAM_CLOSE_EXECUTOR = Executors.newCachedThreadPool(
        new CancellationThreadFactory());

    private final String id = UUID.randomUUID().toString();
    private final String metadataUuid;
    private final String url;
    private final MetadataResourceVisibility visibility;
    private final Boolean approved;
    private final Integer ownerUserId;

    private final Date submittedDateTime = new Date();
    private volatile Date startedDateTime;
    private volatile Date endedDateTime;

    private volatile Status status = Status.PENDING;
    private volatile long bytesTransferred = 0;
    private volatile long totalBytes = -1;
    private volatile String filename;
    private volatile MetadataResource resource;
    private volatile String error;
    private volatile Closeable activeStream;

    public ResourceUploadTask(String metadataUuid, String url, MetadataResourceVisibility visibility,
                               Boolean approved, Integer ownerUserId) {
        this.metadataUuid = metadataUuid;
        this.url = url;
        this.visibility = visibility;
        this.approved = approved;
        this.ownerUserId = ownerUserId;
    }

    public String getId() {
        return id;
    }

    public String getMetadataUuid() {
        return metadataUuid;
    }

    public String getUrl() {
        return url;
    }

    public MetadataResourceVisibility getVisibility() {
        return visibility;
    }

    public Boolean getApproved() {
        return approved;
    }

    public Integer getOwnerUserId() {
        return ownerUserId;
    }

    public Status getStatus() {
        return status;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public long getBytesTransferred() {
        return bytesTransferred;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    /**
     * @return the percentage of the transfer completed, or {@code null} if the
     * total size is unknown (eg. no {@code Content-Length} header).
     */
    public Integer getPercentComplete() {
        if (totalBytes <= 0) {
            return null;
        }
        return (int) Math.min(100, Math.round((bytesTransferred * 100.0) / totalBytes));
    }

    public Date getSubmittedDateTime() {
        return submittedDateTime;
    }

    public Date getStartedDateTime() {
        return startedDateTime;
    }

    public Date getEndedDateTime() {
        return endedDateTime;
    }

    public MetadataResource getResource() {
        return resource;
    }

    public String getError() {
        return error;
    }

    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED;
    }

    public boolean isFinalizing() {
        return status == Status.FINALIZING;
    }

    public boolean isCancelling() {
        return status == Status.CANCELLING;
    }

    @Override
    public void onProgress(long bytesTransferred, long totalBytes) {
        if (!isCancelled()) {
            this.bytesTransferred = bytesTransferred;
            this.totalBytes = totalBytes;
        }
    }

    @Override
    public void onFilenameResolved(String filename) {
        setFilename(filename);
    }

    public synchronized boolean start() {
        if (status != Status.PENDING) {
            return false;
        }

        status = Status.UPLOADING;
        startedDateTime = new Date();
        return true;
    }

    public synchronized boolean startFinalizing() {
        if (status != Status.UPLOADING) {
            return false;
        }

        status = Status.FINALIZING;
        return true;
    }

    public synchronized void complete(MetadataResource resource) {
        if (status != Status.FINALIZING) {
            return;
        }

        this.resource = resource;
        status = Status.COMPLETED;
        endedDateTime = new Date();
    }

    public synchronized void fail(String errorMessage) {
        if (isTerminal()) {
            return;
        }

        error = errorMessage;
        status = Status.FAILED;
        endedDateTime = new Date();
    }

    public boolean cancel() {
        Closeable stream;

        synchronized (this) {
            if (isTerminal() || status == Status.FINALIZING) {
                return false;
            }

            if (status == Status.CANCELLING) {
                return true;
            }

            if (status == Status.PENDING) {
                status = Status.CANCELLED;
                endedDateTime = new Date();
                return true;
            }

            status = Status.CANCELLING;
            stream = activeStream;
        }

        if (stream != null) {
            closeStreamAsync(stream);
        }

        return true;
    }

    public synchronized void markCancelledAfterCleanup() {
        if (status == Status.CANCELLING) {
            status = Status.CANCELLED;
            endedDateTime = new Date();
        }
    }

    @Override
    public boolean isCancelled() {
        return status == Status.CANCELLING || status == Status.CANCELLED;
    }

    @Override
    public void onStreamOpened(Closeable stream) {
        activeStream = stream;

        if (isCancelled()) {
            closeStreamAsync(stream);
        }
    }

    @Override
    public void onStreamClosed(Closeable stream) {
        if (activeStream == stream) {
            activeStream = null;
        }
    }

    private void closeStreamAsync(Closeable stream) {
        STREAM_CLOSE_EXECUTOR.execute(() -> {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Task cancellation already requested.
            }
        });
    }

    private static class CancellationThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "resource-upload-cancel-close-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
