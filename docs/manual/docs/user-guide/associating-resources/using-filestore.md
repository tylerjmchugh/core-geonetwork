# Uploading attachments {#associating_resources_filestore}

!!! info "Version Added"

    3.2


If documents are not available, editors can upload attachments to a metadata record. The attachment is added to the filestore. The filestore can contain any kind of files.

![](img/filestore.png)

To upload a file, click the button and choose a file or drag&drop a file on the button. Files are stored in a folder in the data directory (see [Customizing the data directory](../../install-guide/customizing-data-directory.md)). There is one folder per metadata containing:

-   `public` folder with files accessible to all users
-   `private` folder with files accessible to identified user with download privilege (see [Managing privileges](../publishing/managing-privileges.md))

From the filestore:

-   click the file name to set the URL for the current document to attach
-   click the eye icon to view the document
-   click the locker to change the document visibility
-   click the cross to remove the file.

A file uploaded in this way will be exported in the metadata export file (MEF). Therefore, its URL will not be automatically added to the metadata. The URL is added when attaching the document to a specific element in the metadata (eg. overview, quality report, legend).

## Filestore configuration

By default, the maximum file size is set to 100Mb. This limit is set in `/services/src/main/resources/config-spring-geonetwork.xml` with the parameter `maxUploadSize`.

During startup of the application, this limit can be adjusted by adding the following option to **CATALINA_OPTS**. The value is to be specified in bytes, thus, the following example configures a max upload size of 1 GB:

```
-Dapi.params.maxUploadSize=1000000000
```

Types of attachments allowed to be uploaded can be configured in the system settings.  
See [Metadata configuration](../../administrator-guide/configuring-the-catalog/system-configuration.md#metadata_configuration) for more details.

## Uploading a resource from a URL asynchronously

!!! info "Version Added"

    4.4.13

`PUT .../api/records/{metadataUuid}/attachments?url=...` normally blocks the HTTP request until the
remote file has been fully downloaded and stored, which can time out for large files. Adding
`&async=true` to the request instead returns immediately with `202 Accepted` and a task
description:

```json
{
  "id": "5f2c5b6e-...",
  "metadataUuid": "43d7c186-2187-4bcd-8843-41e575a5ef56",
  "url": "https://example.org/big-file.zip",
  "status": "UPLOADING",
  "bytesTransferred": 10485760,
  "totalBytes": 104857600,
  "percentComplete": 10,
  "filename": "big-file.zip",
  "resource": null,
  "error": null
}
```

The response also includes a `Location` header pointing at the task status URL. Poll
`GET .../api/records/{metadataUuid}/attachments/uploads/{taskId}` until the task reaches one of the
terminal states: `COMPLETED`, `FAILED`, or `CANCELLED`. `GET .../api/records/{metadataUuid}/attachments/uploads`
lists the upload tasks for a record.

Access to task status, listing, and cancellation is restricted to the user who submitted the task
or an Administrator, and the caller must still have edit access to the metadata record.

Status values and meanings:

| Status       | Meaning                                                               |
| ------------ | --------------------------------------------------------------------- |
| `PENDING`    | Waiting for an upload worker.                                         |
| `UPLOADING`  | Downloading or storing the resource.                                  |
| `FINALIZING` | Finishing the upload transaction; cancellation is no longer accepted. |
| `CANCELLING` | Cancellation was requested; the worker is still stopping.             |
| `COMPLETED`  | The upload succeeded. The attachment description is in `resource`.    |
| `FAILED`     | The upload failed. See `error`.                                       |
| `CANCELLED`  | The upload worker has stopped following cancellation.                 |

Notes:

- Send `DELETE` to the task URL to request cancellation.
- A successful `DELETE` may return a task whose status is still `CANCELLING`.
- Continue polling after cancellation until the task reaches a terminal state.
- `percentComplete == 100` does not mean the task is `COMPLETED`; it may still be `FINALIZING`.
- If the remote resource size is unknown, `totalBytes` is `-1` and `percentComplete` is `null`.
- An asynchronous request can initially return `202 Accepted` and later become `FAILED` if its
  resolved filename conflicts with another active upload.
- Terminal tasks are retained for approximately 30 minutes and are cleaned up periodically, so they
  may remain visible for a little longer than 30 minutes.
- Task state is held in memory on the node handling the request and is lost on restart. In a
  clustered deployment, subsequent polling requests must reach the same node that accepted the
  upload.

