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

package uk.gov.hmrc.eacdfileprocessor.helper

import org.bson.types.ObjectId
import play.api.libs.json.{JsValue, Json}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.{FAILED, INITIAL, SCANNED}
import uk.gov.hmrc.eacdfileprocessor.models.*

import java.net.URL
import java.time.Instant

trait TestData:
  val createdAt = Instant.parse("2026-02-18T12:43:58.342Z")

  val readyCallbackBody: ReadyCallbackBody = ReadyCallbackBody(
    reference = Reference("08aad019-7f66-4456-8d52-93f12109876f"),
    downloadUrl = URL("http://localhost:9570/upscan/download/a682288e-57b5-4319-9f04-e37bced82469"),
    uploadDetails = UploadDetails(
      uploadTimestamp = Instant.ofEpochMilli(1771344883),
      checksum = "e3e7d5dd0ea499d5776e3d2710d4d693c7add3cb66fc346c532ddc056b3b919a",
      fileMimeType = "text/csv",
      fileName = "bulk-de-enrol.csv",
      size = 127520L
    )
  )

  val wrongMIMEReadyCallbackBody: ReadyCallbackBody = ReadyCallbackBody(
    reference = Reference("08aad019-7f66-4456-8d52-93f12109876f"),
    downloadUrl = URL("http://localhost:9570/upscan/download/a682288e-57b5-4319-9f04-e37bced82469"),
    uploadDetails = UploadDetails(
      uploadTimestamp = Instant.ofEpochMilli(1771344883),
      checksum = "e3e7d5dd0ea499d5776e3d2710d4d693c7add3cb66fc346c532ddc056b3b919a",
      fileMimeType = "image/png",
      fileName = "bulk-de-enrol.png",
      size = 127520L
    )
  )

  val failedCallbackBody: FailedCallbackBody = FailedCallbackBody(
    reference = Reference("08aad019-7f66-4456-8d52-93f12109876f"),
    failureDetails = ErrorDetails(
      failureReason = "REJECTED",
      message = "MIME type application/pdf is not allowed for service"
    )
  )

  val initiateUploadDetails = UploadedDetails(
    id = ObjectId("6994a038d540b44c4403aee3"),
    reference = Reference("08aad019-7f66-4456-8d52-93f12109876f"),
    status = INITIAL,
    requestorPID = "12345678",
    requestorEmail = "test@hmrc.gov.uk",
    requestorName = "Test User",
    lastUpdatedDateTime = createdAt
  )

  val failedFileDetails = Details.UploadedFailed(
    failureReason = "REJECTED",
    message = "MIME type application/pdf is not allowed for service"
  )

  val successfulUploadedDetails = Details.UploadedSuccessfully(
    name = "bulk-de-enrol.csv",
    mimeType = "text/csv",
    downloadUrl = URL("http://localhost:9570/upscan/download/c5da3bd6-f118-4cde-afff-93f763bf6448"),
    size = Some(32270),
    checksum = "a0acaa6039c1a94c6f5c43f144c5add07de9381f98701cb14c7c6ce2be18020b"
  )

  val scannedUploadedDetails = UploadedDetails(
    id = ObjectId("6994a038d540b44c4403aee3"),
    reference = Reference("08aad019-7f66-4456-8d52-93f12109876f"),
    status = SCANNED,
    requestorPID = "12345678",
    requestorEmail = "test@hmrc.gov.uk",
    requestorName = "Test User",
    details = Some(successfulUploadedDetails),
    lastUpdatedDateTime = createdAt
  )

  val statusDetailsModel = StatusDetailsModel(reference = "08aad019-7f66-4456-8d52-93f12109876f", requestorEmail = "test@hmrc.gov.uk", requestorPID = "12345678", requestorName = "Test User", fileName = Some("test.pdf"), fileStatus = "SCANNED", creationDateTime = Some(createdAt))

  val failedUploadedDetails = UploadedDetails(
    id = ObjectId("6994a038d540b44c4403aee3"),
    reference = Reference("08aad019-7f66-4456-8d52-93f12109876f"),
    status = FAILED,
    requestorPID = "12345678",
    requestorEmail = "test@hmrc.gov.uk",
    requestorName = "Test User",
    details = Some(failedFileDetails),
    lastUpdatedDateTime = createdAt
  )

  val approverDetails = ApproverDetails(
    approverEmail = Some("approverTest@hmrc.gov.uk"),
    approverPID = Some("12345678"),
    approverName = Some("Approver1"),
    errorCode = Some("error code"),
    errorMessage = Some("error message")
  )

  val missingFieldUploadedDetails: JsValue = Json.parse(
    """
      |{
      |  "_id" : {
      |    "$oid": "6994a038d540b44c4403aee3"
      |  },
      |  "reference" : {
      |    "value" : "747c4a0c-a442-4c72-baeb-efb758462602"
      |  },
      |  "status" : "failed",
      |  "requestorPID" : "12345678",
      |  "requestorEmail" : "test@hmrc.gov.uk",
      |  "requestorName" : "Test User",
      |  "details" : {
      |    "message" : "MIME type application/pdf is not allowed for service"
      |  },
      |  "createdAt" : {
      |    "$date": {
      |      "$numberLong": "1771418638342"
      |    }
      |  }
      |}
      |""".stripMargin
  )

  val upscanSuccessResponse: JsValue = Json.parse(
    """
      |{
      |  "reference": "08aad019-7f66-4456-8d52-93f12109876f",
      |  "downloadUrl": "http://localhost:9570/upscan/download/a682288e-57b5-4319-9f04-e37bced82469",
      |  "fileStatus": "READY",
      |  "uploadDetails": {
      |    "size": 127520,
      |    "fileMimeType": "text/csv",
      |    "fileName": "bulk-de-enrol.csv",
      |    "checksum": "e3e7d5dd0ea499d5776e3d2710d4d693c7add3cb66fc346c532ddc056b3b919a",
      |    "uploadTimestamp": "2026-02-17T15:27:29.889028Z"
      |  }
      |}
      |""".stripMargin
  )

  val upscanFailureResponse: JsValue = Json.parse(
    """
      |{
      |  "reference": "08aad019-7f66-4456-8d52-93f12109876f",
      |  "fileStatus": "FAILED",
      |  "failureDetails": {
      |    "failureReason": "QUARANTINE",
      |    "message": "e.g. This file has a virus"
      |  }
      |}
      |""".stripMargin
  )

  val upscanWrongFileStatusResponse: JsValue = Json.parse(
    """
      |{
      |  "reference": "08aad019-7f66-4456-8d52-93f12109876f",
      |  "fileStatus": "PASSED",
      |  "failureDetails": {
      |    "failureReason": "QUARANTINE",
      |    "message": "e.g. This file has a virus"
      |  }
      |}
      |""".stripMargin
  )

  val upscanMissingFileStatusResponse: JsValue = Json.parse(
    """
      |{
      |  "reference": "08aad019-7f66-4456-8d52-93f12109876f",
      |  "failureDetails": {
      |    "failureReason": "QUARANTINE",
      |    "message": "e.g. This file has a virus"
      |  }
      |}
      |""".stripMargin
  )

  val upscanMissingReferenceResponse: JsValue = Json.parse(
    """
      |{
      |  "downloadUrl": "http://localhost:9570/upscan/download/a682288e-57b5-4319-9f04-e37bced82469",
      |  "fileStatus": "READY",
      |  "uploadDetails": {
      |    "size": 127520,
      |    "fileMimeType": "text/csv",
      |    "fileName": "bulk-de-enrol.csv",
      |    "checksum": "e3e7d5dd0ea499d5776e3d2710d4d693c7add3cb66fc346c532ddc056b3b919a",
      |    "uploadTimestamp": "2026-02-17T15:27:29.889028Z"
      |  }
      |}
      |""".stripMargin
  )

  val upscanMissingReferenceFailureResponse: JsValue = Json.parse(
    """
      |{
      |  "fileStatus": "FAILED",
      |  "failureDetails": {
      |    "failureReason": "QUARANTINE",
      |    "message": "e.g. This file has a virus"
      |  }
      |}
      |""".stripMargin
  )

  val upscanMissingDownloadUrlResponse: JsValue = Json.parse(
    """
      |{
      |  "reference": "08aad019-7f66-4456-8d52-93f12109876f",
      |  "fileStatus": "READY",
      |  "uploadDetails": {
      |    "size": 127520,
      |    "fileMimeType": "text/csv",
      |    "fileName": "bulk-de-enrol.csv",
      |    "checksum": "e3e7d5dd0ea499d5776e3d2710d4d693c7add3cb66fc346c532ddc056b3b919a",
      |    "uploadTimestamp": "2026-02-17T15:27:29.889028Z"
      |  }
      |}
      |""".stripMargin
  )

  val updateStatusRequestBody: JsValue = Json.parse(
    """
      |{
      |  "status": "approved",
      |  "approverName": "Approver1",
      |  "approverPID": "12345678",
      |  "approverEmail": "approver1@hmrc.gov.uk"
      |  }
      |""".stripMargin
  )

  val updateStatusRequestBodyWithoutStatus: JsValue = Json.parse(
    """
      |{
      |  "approverName": "Approver1",
      |  "approverPID": "12345678",
      |  "approverEmail": "approver1@hmrc.gov.uk"
      |  }
      |""".stripMargin
  )