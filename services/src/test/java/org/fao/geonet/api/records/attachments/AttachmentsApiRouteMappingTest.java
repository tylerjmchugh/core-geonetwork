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
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Comparator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that the new "/uploads" and "/uploads/{taskId}" mappings added for
 * async resource uploads are matched in preference to the pre-existing
 * catch-all {@code GET /{resourceId:.+}} mapping used to download/patch/delete
 * a resource by name, ie. that the new routes do not get shadowed by it.
 */
public class AttachmentsApiRouteMappingTest {

    private final AntPathMatcher matcher = new AntPathMatcher();

    @Test
    public void uploadsListRouteIsPreferredOverResourceIdCatchAll() throws NoSuchMethodException {
        String uploadsPattern = requestMappingValue("getUploadTasks", String.class, javax.servlet.http.HttpServletRequest.class);
        String resourceIdPattern = requestMappingValue("getResource", String.class, String.class, Boolean.class,
            Integer.class, javax.servlet.http.HttpServletRequest.class, javax.servlet.http.HttpServletResponse.class);

        assertPreferred(uploadsPattern, resourceIdPattern, "/uploads");
    }

    @Test
    public void uploadTaskRouteIsPreferredOverResourceIdCatchAll() throws NoSuchMethodException {
        String uploadTaskPattern = requestMappingValue("getUploadTask", String.class, String.class,
            javax.servlet.http.HttpServletRequest.class);
        String resourceIdPattern = requestMappingValue("getResource", String.class, String.class, Boolean.class,
            Integer.class, javax.servlet.http.HttpServletRequest.class, javax.servlet.http.HttpServletResponse.class);

        assertPreferred(uploadTaskPattern, resourceIdPattern, "/uploads/5f2c5b6e-task");
    }

    private void assertPreferred(String morePreferredPattern, String lessPreferredPattern, String path) {
        assertTrue(morePreferredPattern + " must match " + path, matcher.match(morePreferredPattern, path));

        if (!matcher.match(lessPreferredPattern, path)) {
            // The catch-all pattern doesn't even match this path (e.g. it's confined to a
            // single path segment while the new route spans multiple segments), so there is
            // no possibility of a collision - this is an even stronger guarantee than pattern
            // specificity ordering.
            return;
        }

        Comparator<String> comparator = matcher.getPatternComparator(path);
        assertTrue(morePreferredPattern + " should be more specific than " + lessPreferredPattern + " for path " + path,
            comparator.compare(morePreferredPattern, lessPreferredPattern) < 0);
    }

    private String requestMappingValue(String methodName, Class<?>... paramTypes) throws NoSuchMethodException {
        Method method = AttachmentsApi.class.getMethod(methodName, paramTypes);
        RequestMapping mapping = method.getAnnotation(RequestMapping.class);
        String[] values = mapping.value();
        assertEquals(1, values.length);
        return values[0];
    }
}
