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
import org.fao.geonet.domain.MetadataResourceVisibility;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class ResourceUploadTaskRegistryTest {

    private ResourceUploadTask newTask(String metadataUuid) {
        return new ResourceUploadTask(metadataUuid, "https://example.org/file.zip",
            MetadataResourceVisibility.PUBLIC, false, 1);
    }

    @Test
    public void registersAndRetrievesTaskById() {
        ResourceUploadTaskRegistry registry = new ResourceUploadTaskRegistry();
        ResourceUploadTask task = newTask("uuid-1");
        registry.register(task);

        assertEquals(task, registry.get(task.getId()));
        assertNull(registry.get("unknown-id"));

        registry.destroy();
    }

    @Test
    public void listsTasksByMetadataUuidMostRecentFirst() throws InterruptedException {
        ResourceUploadTaskRegistry registry = new ResourceUploadTaskRegistry();
        ResourceUploadTask first = newTask("uuid-1");
        registry.register(first);
        Thread.sleep(5);
        ResourceUploadTask second = newTask("uuid-1");
        registry.register(second);
        registry.register(newTask("uuid-2"));

        List<ResourceUploadTask> tasks = registry.getByMetadataUuid("uuid-1");
        assertEquals(2, tasks.size());
        assertEquals(second.getId(), tasks.get(0).getId());
        assertEquals(first.getId(), tasks.get(1).getId());

        registry.destroy();
    }

    @Test
    public void sweepRemovesExpiredTerminalTasksOnly() throws Exception {
        // Very small TTL so the sweep can be exercised deterministically.
        ResourceUploadTaskRegistry registry = new ResourceUploadTaskRegistry(1, 500);

        ResourceUploadTask completedOld = newTask("uuid-1");
        registry.register(completedOld);
        completedOld.start();
        completedOld.startFinalizing();
        completedOld.complete(null);

        ResourceUploadTask running = newTask("uuid-2");
        registry.register(running);
        running.start();

        Thread.sleep(20);

        invokeSweep(registry);

        assertNull("Expired terminal task should have been swept", registry.get(completedOld.getId()));
        assertEquals("Still-running task must not be swept", running, registry.get(running.getId()));

        registry.destroy();
    }

    @Test
    public void evictsOldestTerminalTaskWhenOverCapacity() throws InterruptedException {
        ResourceUploadTaskRegistry registry = new ResourceUploadTaskRegistry(TimeUnit_HOUR_MILLIS(), 2);

        ResourceUploadTask oldest = newTask("uuid-1");
        registry.register(oldest);
        oldest.start();
        oldest.startFinalizing();
        oldest.complete(null);

        Thread.sleep(5);

        ResourceUploadTask newest = newTask("uuid-2");
        registry.register(newest);
        newest.start();
        newest.startFinalizing();
        newest.complete(null);

        // Registering a third task exceeds the retention target of 2 and should evict the oldest terminal task.
        ResourceUploadTask third = newTask("uuid-3");
        registry.register(third);

        assertNull(registry.get(oldest.getId()));
        assertEquals(newest, registry.get(newest.getId()));
        assertEquals(third, registry.get(third.getId()));

        registry.destroy();
    }

    @Test
    public void competingFilenameClaimsAreDetectedAndRetained() throws Exception {
        ResourceUploadTaskRegistry registry = new ResourceUploadTaskRegistry();
        try {
            ResourceUploadTask first = newTask("uuid-1");
            ResourceUploadTask second = newTask("uuid-1");
            registry.register(first);
            registry.register(second);

            String filename = "conflict.txt";

            // First claim should succeed and set the filename on the first task
            registry.resolveFilenameAndCheck("uuid-1", filename, first);
            // Second claim should be rejected
            try {
                registry.resolveFilenameAndCheck("uuid-1", filename, second);
                fail("Expected ResourceAlreadyExistException for competing filename claim");
            } catch (ResourceAlreadyExistException expected) {
                // expected
            }

            // Both tasks should retain the resolved filename
            assertEquals(filename, first.getFilename());
            assertEquals(filename, second.getFilename());
        } finally {
            registry.destroy();
        }
    }

    private static long TimeUnit_HOUR_MILLIS() {
        return 60L * 60L * 1000L;
    }

    private void invokeSweep(ResourceUploadTaskRegistry registry) throws Exception {
        java.lang.reflect.Method sweep = ResourceUploadTaskRegistry.class.getDeclaredMethod("sweep");
        sweep.setAccessible(true);
        sweep.invoke(registry);
    }
}
