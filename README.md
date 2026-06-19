# eacd-file-processor

This service is used as part of bulk de-enrolment processing.

A helpdesk user uploads a document and this service stores metadata, receives status callbacks, and exposes
retrieval/status endpoints.

## Running service

```bash
sbt "run 9867"
```

## Running tests

```bash
sbt clean coverage test it/test coverageReport
```

### Running the unit test suite

```bash
sbt clean coverage test coverageReport
```

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

| Method              | Path                                                           | Controller                                                           | Purpose                                                            |
|---------------------|----------------------------------------------------------------|----------------------------------------------------------------------|--------------------------------------------------------------------|
| POST                | `/eacd-file-processor/callback`                                | `CallbackController.callback`                                        | Receive Upscan callback (`READY` / `FAILED`) and update file state |
| POST                | `/eacd-file-processor/initiate`                                | `InitiateFileStorageController.initiateFileRecordStore()`            | Create initial file record for helpdesk request                    |
| PUT                 | `/eacd-file-processor/status/:reference`                       | `StatusController.updateStatus(reference: String)`                   | Update file status and optional approver/error details             |
| GET                 | `/eacd-file-processor/files/:status`                           | `StatusController.getFilesStatus(status: String)`                    | List file records by status                                        |
| GET                 | `/eacd-file-processor/file/:reference`                         | `FileController.getFile(reference: String)`                          | Download file content by reference                                 |
| GET *(support)*     | `/eacd-file-processor/support-tool/file-status-count`          | `support.controllers.StatusController.getAllStatusCounts`            | Return aggregate counts across statuses                            |
| PUT *(testOnly)*    | `/test-only/eacd-file-processor/document/:reference/:fileName` | `testOnly.controllers.TestController.putObject(reference, fileName)` | Seed object store content for tests                                |
| DELETE *(testOnly)* | `/test-only/eacd-file-processor/drop`                          | `testOnly.controllers.TestController.deleteAllObjects()`             | Clear test object store content                                    |

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
  }
]
```

Responses:

- `200 OK` with JSON array of `{status, count}`
- `204 No Content` when there are no file records

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

## License

This code is open source software licensed under
the [Apache 2.0 License](http://www.apache.org/licenses/LICENSE-2.0.html).
