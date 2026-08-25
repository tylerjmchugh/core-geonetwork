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

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ProgressReportingInputStreamTest {

    @Test
    public void reportsCumulativeBytesReadOnBulkReads() throws IOException {
        byte[] data = new byte[1000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }

        List<Long> reportedBytes = new ArrayList<>();
        List<Long> reportedTotals = new ArrayList<>();
        try (ProgressReportingInputStream is = new ProgressReportingInputStream(
                new ByteArrayInputStream(data), data.length, (bytesTransferred, totalBytes) -> {
            reportedBytes.add(bytesTransferred);
            reportedTotals.add(totalBytes);
        })) {
            byte[] buffer = new byte[128];
            byte[] readData = new byte[data.length];
            int offset = 0;
            int n;
            while ((n = is.read(buffer)) != -1) {
                System.arraycopy(buffer, 0, readData, offset, n);
                offset += n;
            }
            assertArrayEquals(data, readData);
            assertEquals(data.length, is.getBytesTransferred());
        }

        assertEquals(data.length, (long) reportedBytes.get(reportedBytes.size() - 1));
        for (long total : reportedTotals) {
            assertEquals(data.length, total);
        }
    }

    @Test
    public void reportsProgressOnSingleByteReads() throws IOException {
        byte[] data = new byte[]{1, 2, 3};
        long[] lastReported = new long[1];
        try (ProgressReportingInputStream is = new ProgressReportingInputStream(
                new ByteArrayInputStream(data), data.length, (bytesTransferred, totalBytes) -> lastReported[0] = bytesTransferred)) {
            while (is.read() != -1) {
                // consume
            }
        }
        assertEquals(data.length, lastReported[0]);
    }

    @Test
    public void doesNotFailWithoutAListener() throws IOException {
        byte[] data = new byte[]{1, 2, 3};
        try (ProgressReportingInputStream is = new ProgressReportingInputStream(new ByteArrayInputStream(data), -1, null)) {
            while (is.read() != -1) {
                // consume, must not throw despite the null listener
            }
        }
    }
}
