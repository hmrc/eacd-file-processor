/*
 * Copyright 2026 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.eacdfileprocessor.models

import play.api.libs.json.{JsValue, Json}
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport

import java.time.Instant

class FileDetailResponseSpec extends TestSupport {

  val now: Instant = Instant.parse("2026-01-01T00:00:00Z")

  val fileDetailResponse: FileDetailResponse = FileDetailResponse(
    fileName             = "test-file.csv",
    reference            = "ref-123",
    creationDateTime     = now,
    errorCode            = Some("ERR001"),
    errorMessage         = Some("Some error"),
    fileStatus           = "UPLOADED",
    lastUpdatedDateTime  = now,
    requestorEmail       = "requestor@hmrc.gov.uk",
    requestorPID         = "PID123",
    requestorName        = "Test Requestor",
    downloadUrl          = "https://example.com/download",
    fileMimeType         = "text/csv",
    uploadTimestamp      = now,
    checksum             = "abc123checksum",
    size                 = 1024L,
    failureReason        = Some("QUARANTINE"),
    failureMessage       = Some("File was quarantined"),
    approverEmail        = Some("approver@hmrc.gov.uk"),
    approverPID          = Some("APID456"),
    approverName         = Some("Test Approver"),
    approvalDateTime     = Some(now),
    totalEntryCount      = 100,
    totalSuccessCount    = 95,
    totalFailureCount    = 5
  )

  val fileDetailResponseJson: JsValue = Json.parse(
    """
      |{
      |  "fileName": "test-file.csv",
      |  "reference": "ref-123",
      |  "creationDateTime": "2026-01-01T00:00:00Z",
      |  "errorCode": "ERR001",
      |  "errorMessage": "Some error",
      |  "fileStatus": "UPLOADED",
      |  "lastUpdatedDateTime": "2026-01-01T00:00:00Z",
      |  "requestorEmail": "requestor@hmrc.gov.uk",
      |  "requestorPID": "PID123",
      |  "requestorName": "Test Requestor",
      |  "downloadUrl": "https://example.com/download",
      |  "fileMimeType": "text/csv",
      |  "uploadTimestamp": "2026-01-01T00:00:00Z",
      |  "checksum": "abc123checksum",
      |  "size": 1024,
      |  "failureReason": "QUARANTINE",
      |  "failureMessage": "File was quarantined",
      |  "approverEmail": "approver@hmrc.gov.uk",
      |  "approverPID": "APID456",
      |  "approverName": "Test Approver",
      |  "approvalDateTime": "2026-01-01T00:00:00Z",
      |  "totalEntryCount": 100,
      |  "totalSuccessCount": 95,
      |  "totalFailureCount": 5
      |}
      |""".stripMargin)

  "FileDetailResponse" should {
    "serialize to JSON" in {
      Json.toJson(fileDetailResponse) mustBe fileDetailResponseJson
    }
    "deserialize from JSON" in {
      fileDetailResponseJson.as[FileDetailResponse] mustBe fileDetailResponse
    }
    "serialize and deserialize with None optional fields" in {
      val minimal = fileDetailResponse.copy(
        errorCode        = None,
        errorMessage     = None,
        failureReason    = None,
        failureMessage   = None,
        approverEmail    = None,
        approverPID      = None,
        approverName     = None,
        approvalDateTime = None
      )
      Json.toJson(minimal).as[FileDetailResponse] mustBe minimal
    }
  }
}