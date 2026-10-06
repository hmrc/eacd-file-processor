# eacd-file-processor

This service is used as part of bulk de-enrolment processing.

A helpdesk user uploads a document and this service stores metadata, receives status callbacks, and exposes
retrieval/status endpoints.

## Run Service locally:

To run the service locally you will need to run the following command in the root of the project:

```
sbt run
```

To run the service locally with testOnly routes you will need to run the following command in the root of the project:

```
sbt "run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes"
```

## Run Tests

- Run Unit Tests: `sbt test`
- Run Integration Tests: `sbt it/test`
- Run Unit and Integration Tests: `sbt test  it/test`
- Run Unit and Integration Tests with coverage report: `./run_all_tests.sh` (run `chmod +x ./run_all_tests.sh`first to add
  executable permissions before running the shell script) <br/> which runs
  `clean coverage test it/test coverageReport dependencyUpdates`


## API overview

Routes are mounted from:
- `conf/prod.routes`
- `conf/app.routes`
- `conf/support.routes`

Base paths:

- Main API: `/eacd-file-processor`
- Support API: `/eacd-file-processor/support-tool`
- Health routes: mounted at `/` via `health.Routes`

## API Table

| Method              | Path                                                           | Purpose                                                                                                                                 |
|---------------------|----------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| POST                | `/eacd-file-processor/callback`                                | Receive Upscan callback (`READY` / `FAILED`) and update file state                                                                      |
| POST                | `/eacd-file-processor/initiate`                                | Create initial file record for helpdesk request                                                                                         |
| PUT                 | `/eacd-file-processor/status/:reference`                       | Update file status and optional approver/error details                                                                                  |
| GET                 | `/eacd-file-processor/files/:status`                           | List file records by status                                                                                                             |
| GET                 | `/eacd-file-processor/file/:reference`                         | Download file content by reference                                                                                                      |
| GET *(support)*     | `/eacd-file-processor/support-tool/file-status-count`          | Return aggregate counts across statuses                                                                                                 |
| GET *(support)*     | `/eacd-file-processor/support-tool/file-detail/:reference`     | Return file detail by reference                                                                                                         |
| GET *(support)*     | `/eacd-file-processor/support-tool/file-errors/:reference`     | Return file error records by reference in csv format                                                                                    |
| PUT *(testOnly)*    | `/test-only/eacd-file-processor/document/:reference/:fileName` | Seed object store content for tests                                                                                                     |
| DELETE *(testOnly)* | `/test-only/eacd-file-processor/drop`                          | Clear test object store content                                                                                                         |
| GET *(testOnly)*    | `/test-only/eacd-file-processor/invoke/:jobName`               | Manually invokes the scheduled job                                                                                                      |

## API reference

### `POST /eacd-file-processor/callback`

Controller: `CallbackController.callback`

Accepts callback payloads with `fileStatus` discriminator.

Ready callback example:

```json
{
  "fileStatus": "READY",
  "reference": "ref-123",
  "downloadUrl": "https://example.com/files/ref-123",
  "uploadDetails": {
    "uploadTimestamp": "2026-01-01T10:00:00Z",
    "checksum": "abc123",
    "fileMimeType": "application/pdf",
    "fileName": "doc.pdf",
    "size": 1024
  }
}
```

Failed callback example:

```json
{
  "fileStatus": "FAILED",
  "reference": "ref-123",
  "failureDetails": {
    "failureReason": "QUARANTINE",
    "message": "File rejected by scanner"
  }
}
```

Response:

- `204 No Content` (no response body)

### `POST /eacd-file-processor/initiate`

Controller: `InitiateFileStorageController.initiateFileRecordStore()`

Request payload (`HelpdeskInitiateRequestModel`):

- `reference` (string, mandatory)
- `requestorPID` (string, mandatory)
- `requestorEmail` (string, mandatory, valid email)
- `requestorName` (string, mandatory)

Example request:

```json
{
  "reference": "ext-ref-001",
  "requestorPID": "PID123",
  "requestorEmail": "user@hmrc.gov.uk",
  "requestorName": "John Smith"
}
```

Responses:

- `201 Created` (no response body)
- `400 Bad Request`:
    - `{"errorCode":"MANDATORY_FIELDS_MISSING","errorMessage":"Mandatory fields missing"}`
    - `{"errorCode":"INVALID_JSON","errorMessage":"Invalid JSON payload"}`
    - `{"errorCode":"INVALID_REQUESTOR_EMAIL","errorMessage":"Invalid requestor email"}`
    - `{"errorCode":"DUPLICATE_EXTERNAL_FILE_REF","errorMessage":"Duplicate external file reference"}`
- `500 Internal Server Error`:
    - `{"errorCode":"SERVICE_UNAVAILABLE","errorMessage":"An unexpected error has occurred"}`

### `PUT /eacd-file-processor/status/:reference`

Controller: `StatusController.updateStatus(reference: String)`

Request payload (`StatusApproverDetails`):

- `status` (string, mandatory)
- `approverEmail` (string, optional)
- `approverPID` (string, optional)
- `approverName` (string, optional)
- `errorCode` (string, optional)
- `errorMessage` (string, optional)

Example approval request:

```json
{
  "status": "approved",
  "approverName": "John Approver",
  "approverEmail": "approver1@hmrc.gov.uk",
  "approverPID": "23456789"
}
```

Example rejection request:

```json
{
  "status": "uploadRejected",
  "errorCode": "INVALID_FILE",
  "errorMessage": "File contains virus"
}
```

Responses:

- `204 No Content` (no response body)
- `400 Bad Request`:
    - `{"errorCode":"INVALID_FILE_REF","errorMessage":"File reference doesn't exist"}`
- `500 Internal Server Error` (error JSON)

### `GET /eacd-file-processor/files/:status`

Controller: `StatusController.getFilesStatus(status: String)`

Returns file records for a given status.

Valid statuses include:

- `scanned`
- `failed`
- `stored`
- `uploaded`
- `uploadRejected`
- `rejected`
- `approved`
- `processing`
- `processedWithErrors`
- `processedSuccessfully`
- `processedWithCountMismatch`

Example response (`200 OK`):

```json
[
  {
    "reference": "ext-ref-001",
    "status": "uploaded",
    "requestorPID": "PID123",
    "requestorEmail": "user@hmrc.gov.uk",
    "requestorName": "John Smith",
    "creationDateTime": "2026-06-19T10:00:00Z"
  }
]
```

Responses:

- `200 OK` with JSON array of uploaded detail records
- `204 No Content` when no records match
- `400 Bad Request`:
    - `{"errorCode":"STATUS_INVALID","errorMessage":"Invalid status"}`

### `GET /eacd-file-processor/file/:reference`

Controller: `FileController.getFile(reference: String)`

Returns file content as a binary stream.

Responses:

- `200 OK` with chunked binary body
- `204 No Content` if file/reference/details are not available

### `GET /eacd-file-processor/support-tool/file-status-count`

Controller: `uk.gov.hmrc.eacdfileprocessor.support.controllers.StatusController.getAllStatusCounts`

Returns counts for all known statuses.

Example response (`200 OK`):

```json
[
  {
    "status": "scanned",
    "count": 1
  },
  {
    "status": "failed",
    "count": 1
  },
  {
    "status": "stored",
    "count": 0
  },
  {
    "status": "uploaded",
    "count": 0
  },
  {
    "status": "uploadRejected",
    "count": 0
  },
  {
    "status": "rejected",
    "count": 0
  },
  {
    "status": "approved",
    "count": 0
  },
  {
    "status": "processing",
    "count": 0
  },
  {
    "status": "processedWithErrors",
    "count": 0
  },
  {
    "status": "processedSuccessfully",
    "count": 0
  },
  {
    "status": "processedWithCountMismatch",
    "count": 0
  }
]
```

Responses:

- `200 OK` with JSON array of `{status, count}`
- `204 No Content` when there are no file records

### `GET /eacd-file-processor/support-tool/file-detail/:reference`

Controller: `uk.gov.hmrc.eacdfileprocessor.support.controllers.FileDetailsController.getFileDetail(reference: String)`

Returns detailed metadata for a file reference.

Example response (`200 OK`):

```json
{
  "id": "67b48d6f7e14f2a5c46d4f3e",
  "reference": "08aad019-7f66-4456-8d52-93f12109876f",
  "status": "approved",
  "requestorPID": "12345678",
  "requestorEmail": "test@hmrc.gov.uk",
  "requestorName": "Test User",
  "details": {
    "name": "bulk-de-enrol.csv",
    "mimeType": "text/csv",
    "downloadUrl": "http://localhost:9570/upscan/download/c5da3bd6-f118-4cde-afff-93f763bf6448",
    "size": 32270,
    "checksum": "a0acaa6039c1a94c6f5c43f144c5add07de9381f98701cb14c7c6ce2be18020b"
  },
  "approverDetails": {
    "approverEmail": "approverTest@hmrc.gov.uk",
    "approverPID": "12345678",
    "approverName": "Approver1",
    "errorCode": "error code",
    "errorMessage": "error message"
  },
  "totalEntryCount": 100,
  "uploadedDateTime": null,
  "lastUpdatedDateTime": null,
  "approvedAtDateTime": "2026-02-18T12:43:58.342Z",
  "creationDateTime": "2026-02-18T12:43:58.342Z",
  "totalFailureCount": 5,
  "totalSuccessCount": 95
}
```

Responses:

- `200 OK` with JSON file detail
- `204 No Content` when no record exists for the reference
- `500 Internal Server Error`: `Error retrieving details`

### `GET /eacd-file-processor/support-tool/file-errors/:reference`

Controller: `uk.gov.hmrc.eacdfileprocessor.support.controllers.FileController.getFileErrors(reference: String)`

Returns file validation errors as downloadable CSV.

CSV response format:

```csv
reference,fileName,recordDetail,errorMessage,creationDateTime
08aad019-7f66-4456-8d52-93f12109876f,file1.csv,record1,Invalid format,2024-01-01T12:00:00Z
```

Responses:

- `200 OK` with `text/csv; charset=utf-8` and `Content-Disposition` attachment header
- `204 No Content` when no validation errors exist for the reference

## Error response model

Where applicable, errors use:

```json
{
  "errorCode": "SOME_CODE",
  "errorMessage": "A human-readable message"
}
```

## Test-only endpoints

Defined in `conf/testOnlyDoNotUseInAppConf.routes` and only available when test routing is enabled:

- `PUT /test-only/eacd-file-processor/document/:reference/:fileName`
- `DELETE /test-only/eacd-file-processor/drop`
- `GET /test-only/eacd-file-processor/invoke/:jobname`

## Scheduled Jobs
### ApprovedFileProcessingJob
Processes the oldest approved file from mongoDB and retrieves the file from Object Store then creates de-enrolment work items.
### DeEnrolmentWorkItemPullJob
Pulls uncompleted de-enrolment work items from mongoDB. The ones pass validation will be de-enrolled by calling ES1 and ES9.
### FileStatusUpdateJob
Updates file status to processedWithErrors, processedSuccessfully or processedWithCountMismatch only if the current status is processing.
### ExpiredFileDeletionJob
Deletes file from mongoDB that are older than 60 days(depends on fileExpiryDays value in application.conf). Files are deleted from Object Store only if the stage is beyond initial.

## License

This code is open source software licensed under
the [Apache 2.0 License](http://www.apache.org/licenses/LICENSE-2.0.html).
