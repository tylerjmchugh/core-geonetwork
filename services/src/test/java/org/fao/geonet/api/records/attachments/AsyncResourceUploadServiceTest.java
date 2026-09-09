/*
 * =============================================================================
 * ===    Copyright (C) 2001-2026 Food and Agriculture Organization of the
 * ===    United Nations (FAO-UN), United Nations World Food Programme (WFP)
 * ===    and United Nations Environment Programme (UNEP)
 * ===
 * ===    This program is free software; you can redistribute it and/or modify
 * ===    it under the terms of the GNU General Public License as published by
 * ===    the Free Software Foundation; either version 2 of the License, or (at
 * ===    your option) any later version.
 * ===
 * ===    This program is distributed in the hope that it will be useful, but
 * ===    WITHOUT ANY WARRANTY; without even the implied warranty of
 * ===    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * ===    General Public License for more details.
 * ===
 * ===    You should have received a copy of the GNU General Public License
 * ===    along with this program; if not, write to the Free Software
 * ===    Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
 * ===
 * ===    Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
 * ===    Rome - Italy. email: geonetwork@osgeo.org
 * ==============================================================================
 */
package org.fao.geonet.api.records.attachments;

import org.fao.geonet.domain.MetadataResourceVisibility;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URL;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AsyncResourceUploadServiceTest {

    private AsyncResourceUploadService service;
    private ResourceUploadTaskRegistry registry;

    @Before
    public void setUp() {
        service = new AsyncResourceUploadService();
        registry = new ResourceUploadTaskRegistry();
        ReflectionTestUtils.setField(service, "registry", registry);
    }

    @After
    public void tearDown() {
        service.destroy();
        registry.destroy();
    }

    @Test
    public void hasInProgressUploadMatchesPendingTaskByFilename() throws Exception {
        ResourceUploadTask existing = new ResourceUploadTask(
            "uuid-1", "http://example.test/path/report.csv", MetadataResourceVisibility.PUBLIC, false, 101);
        existing.setFilename("report.csv");
        registry.register(existing);

        boolean blocked = service.hasInProgressUpload(
            "uuid-1",
            new URL("file:/tmp/report.csv"));

        assertTrue(blocked);
    }

    @Test
    public void hasInProgressUploadIgnoresTerminalTask() throws Exception {
        ResourceUploadTask completed = new ResourceUploadTask(
            "uuid-1", "http://example.test/path/report.csv", MetadataResourceVisibility.PUBLIC, false, 101);
        completed.setFilename("report.csv");
        completed.complete(null);
        registry.register(completed);

        boolean blocked = service.hasInProgressUpload(
            "uuid-1",
            new URL("file:/tmp/report.csv"));

        assertFalse(blocked);
    }
}



