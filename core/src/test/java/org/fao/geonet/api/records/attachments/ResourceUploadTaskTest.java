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

import org.fao.geonet.domain.MetadataResource;
import org.fao.geonet.domain.MetadataResourceVisibility;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

public class ResourceUploadTaskTest {

    @Test
    public void startsAsPending() {
        ResourceUploadTask task = new ResourceUploadTask("uuid-1", "https://example.org/file.zip",
            MetadataResourceVisibility.PUBLIC, false, 42);
        assertEquals(ResourceUploadTask.Status.PENDING, task.getStatus());
        assertTrue(!task.isTerminal());
        assertNull(task.getPercentComplete());
    }

    @Test
    public void reportsPercentCompleteFromProgress() {
        ResourceUploadTask task = new ResourceUploadTask("uuid-1", "https://example.org/file.zip",
            MetadataResourceVisibility.PUBLIC, false, 42);
        task.start();
        task.onProgress(25, 100);
        assertEquals(ResourceUploadTask.Status.UPLOADING, task.getStatus());
        assertEquals(Integer.valueOf(25), task.getPercentComplete());

        task.onProgress(100, 100);
        assertEquals(Integer.valueOf(100), task.getPercentComplete());
    }

    @Test
    public void percentCompleteIsNullWhenTotalUnknown() {
        ResourceUploadTask task = new ResourceUploadTask("uuid-1", "https://example.org/file.zip",
            MetadataResourceVisibility.PUBLIC, false, 42);
        task.onProgress(500, -1);
        assertNull(task.getPercentComplete());
    }

    @Test
    public void completingSetsResourceAndTerminalStatus() {
        ResourceUploadTask task = new ResourceUploadTask("uuid-1", "https://example.org/file.zip",
            MetadataResourceVisibility.PUBLIC, false, 42);
        MetadataResource resource = Mockito.mock(MetadataResource.class);

        task.start();
        task.startFinalizing();
        task.complete(resource);

        assertEquals(ResourceUploadTask.Status.COMPLETED, task.getStatus());
        assertTrue(task.isTerminal());
        assertEquals(resource, task.getResource());
        assertNull(task.getError());
    }

    @Test
    public void failingSetsErrorAndTerminalStatus() {
        ResourceUploadTask task = new ResourceUploadTask("uuid-1", "https://example.org/file.zip",
            MetadataResourceVisibility.PUBLIC, false, 42);

        task.start();
        task.fail("boom");

        assertEquals(ResourceUploadTask.Status.FAILED, task.getStatus());
        assertTrue(task.isTerminal());
        assertNull(task.getResource());
        assertEquals("boom", task.getError());
    }

    @Test
    public void cancelPendingTaskMovesDirectlyToCancelled() {
        ResourceUploadTask task = new ResourceUploadTask("uuid-1", "https://example.org/file.zip",
            MetadataResourceVisibility.PUBLIC, false, 42);

        assertTrue(task.cancel());
        assertEquals(ResourceUploadTask.Status.CANCELLED, task.getStatus());
        assertTrue(task.isTerminal());
        assertFalse(task.start());
    }

    @Test
    public void cancelUploadingTaskUsesCancellingThenCancelledAfterCleanup() {
        ResourceUploadTask task = new ResourceUploadTask("uuid-1", "https://example.org/file.zip",
            MetadataResourceVisibility.PUBLIC, false, 42);

        task.start();
        assertTrue(task.cancel());
        assertEquals(ResourceUploadTask.Status.CANCELLING, task.getStatus());
        assertTrue(task.isCancelled());

        task.markCancelledAfterCleanup();
        assertEquals(ResourceUploadTask.Status.CANCELLED, task.getStatus());
    }

    @Test
    public void cancelIsRejectedAfterFinalizingStarts() {
        ResourceUploadTask task = new ResourceUploadTask("uuid-1", "https://example.org/file.zip",
            MetadataResourceVisibility.PUBLIC, false, 42);

        task.start();
        assertTrue(task.startFinalizing());
        assertFalse(task.cancel());
        assertEquals(ResourceUploadTask.Status.FINALIZING, task.getStatus());
    }

    @Test
    public void snapshotIsStableAfterOriginalChanges() {
        ResourceUploadTask task = new ResourceUploadTask("uuid-1", "https://example.org/file.zip",
            MetadataResourceVisibility.PUBLIC, false, 42);

        // Start and set some progress and filename
        task.start();
        task.setFilename("big-file.zip");
        task.onProgress(10, 100);

        ResourceUploadTask snapshot = task.snapshot();

        // Mutate the original task to completion
        task.startFinalizing();
        MetadataResource resource = Mockito.mock(MetadataResource.class);
        task.complete(resource);

        // Snapshot should remain as it was when taken
        assertEquals(ResourceUploadTask.Status.UPLOADING, snapshot.getStatus());
        assertEquals("big-file.zip", snapshot.getFilename());
        assertEquals(Integer.valueOf(10), snapshot.getPercentComplete());
        assertNull(snapshot.getResource());
        assertNull(snapshot.getEndedDateTime());
    }

    @Test
    public void terminalStatesHaveEndedDateTime() {
        // COMPLETED
        ResourceUploadTask completed = new ResourceUploadTask("uuid-1", "https://example.org/file.zip",
            MetadataResourceVisibility.PUBLIC, false, 42);
        completed.start();
        completed.startFinalizing();
        completed.complete(Mockito.mock(MetadataResource.class));
        assertNotNull(completed.getEndedDateTime());

        // FAILED
        ResourceUploadTask failed = new ResourceUploadTask("uuid-2", "https://example.org/file.zip",
            MetadataResourceVisibility.PUBLIC, false, 42);
        failed.start();
        failed.fail("boom");
        assertNotNull(failed.getEndedDateTime());

        // CANCELLED directly from PENDING
        ResourceUploadTask cancelledDirect = new ResourceUploadTask("uuid-3", "https://example.org/file.zip",
            MetadataResourceVisibility.PUBLIC, false, 42);
        cancelledDirect.cancel();
        assertNotNull(cancelledDirect.getEndedDateTime());

        // CANCELLED after CANCELLING
        ResourceUploadTask cancelledAfter = new ResourceUploadTask("uuid-4", "https://example.org/file.zip",
            MetadataResourceVisibility.PUBLIC, false, 42);
        cancelledAfter.start();
        cancelledAfter.cancel();
        cancelledAfter.markCancelledAfterCleanup();
        assertNotNull(cancelledAfter.getEndedDateTime());
    }
}
