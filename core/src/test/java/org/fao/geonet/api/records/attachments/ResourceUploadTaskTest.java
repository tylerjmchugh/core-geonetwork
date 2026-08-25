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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
        assertEquals(ResourceUploadTask.Status.RUNNING, task.getStatus());
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
}
