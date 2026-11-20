package com.termux.app.workspace;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class GoogleDriveApiClientTest {

    @Test
    public void parsesAccessNotConfigured403WithActivationUrl() {
        String rawJson = "{\n"
            + "  \"error\": {\n"
            + "    \"code\": 403,\n"
            + "    \"message\": \"Google Drive API has not been used in project 90449254671 before or it is disabled. Enable it by visiting https://console.developers.google.com/apis/api/drive.googleapis.com/overview?project=90449254671 then retry.\",\n"
            + "    \"errors\": [{\n"
            + "      \"reason\": \"accessNotConfigured\",\n"
            + "      \"extendedHelp\": \"https://console.developers.google.com/apis/api/drive.googleapis.com/overview?project=90449254671\"\n"
            + "    }]\n"
            + "  }\n"
            + "}";

        GoogleDriveApiClient.DriveApiException ex = GoogleDriveApiClient.buildDriveException(403, rawJson);
        assertEquals(403, ex.httpCode);
        assertEquals("accessNotConfigured", ex.reason);
        assertTrue(ex.isAccessNotConfigured());
        assertFalse(ex.isInsufficientPermissions());
        assertNotNull(ex.helpUrl);
        assertTrue(ex.helpUrl.contains("drive.googleapis.com"));
        assertTrue(ex.getVietnameseGuidance().contains("accessNotConfigured"));
    }

    @Test
    public void parsesInsufficientPermissionsAndAbuse403() {
        String insufficientJson = "{\"error\":{\"code\":403,\"message\":\"Request had insufficient authentication scopes.\",\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}";
        GoogleDriveApiClient.DriveApiException ex1 = GoogleDriveApiClient.buildDriveException(403, insufficientJson);
        assertTrue(ex1.isInsufficientPermissions());
        assertFalse(ex1.isAccessNotConfigured());

        String abuseJson = "{\"error\":{\"code\":403,\"message\":\"This file has been identified as malware\",\"errors\":[{\"reason\":\"cannotDownloadAbusiveFile\"}]}}";
        GoogleDriveApiClient.DriveApiException ex2 = GoogleDriveApiClient.buildDriveException(403, abuseJson);
        assertTrue(ex2.isCannotDownloadAbusiveFile());
    }
}
