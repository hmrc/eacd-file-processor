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

package uk.gov.hmrc.eacdfileprocessor.services

import org.apache.pekko.stream.Materializer
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalactic.Prettifier.default
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.http.Status.{BAD_REQUEST, NO_CONTENT, SERVICE_UNAVAILABLE}
import play.api.libs.json.Json
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport, UnitSpec}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.{APPROVED, INITIAL, REJECTED, SCANNED, STORED, UPLOADED, UPLOADREJECTED}
import uk.gov.hmrc.eacdfileprocessor.models.{ApiErrorResponse, FileStatus, StatusApproverDetails}
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository

import scala.concurrent.Future

class StatusServiceSpec extends TestSupport with TestData with UnitSpec:
  implicit val mat: Materializer = mock[Materializer]
  private val mockAppConfig = mock[AppConfig]
  private lazy val repository = mock[FileRepository]
  private lazy val approverMissingFieldErrorResponse = Json.toJson(ApiErrorResponse("APPROVER_FIELDS_MISSING", "Approver fields are missing for status approved"))
  private lazy val invalidStatusTransitionErrorResponse = Json.toJson(ApiErrorResponse("INVALID_STATUS_TRANSITION", "Invalid status transition"))
  private lazy val errorFieldsMissingErrorResponse = Json.toJson(ApiErrorResponse("ERROR_FIELDS_MISSING", "Error fields are missing for status failed"))
  private lazy val alreadyAtStatusErrorResponse = Json.toJson(ApiErrorResponse("ALREADY_AT_STATUS", "Already at the requested status"))
  private lazy val serviceUnavailableErrorResponse = Json.toJson(ApiErrorResponse("SERVICE_UNAVAILABLE", "An unexpected error has occurred"))

  private val statusService = StatusService(repository)

  when(mockAppConfig.timeToLive).thenReturn("3")

  "StatusService" must {
    "updateStatus" must {
      "return NO_CONTENT when updating to uploadRejected, correct information is supplied and current status is initial" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "uploadRejected",
          errorCode = Some("error code"),
          errorMessage = Some("error message")
        )
        when(repository.updateStatusAndApproverDetails(any(), any(), any(), any(), any())).thenReturn(Future.successful(Some(scannedUploadedDetails)))
        val result = await(statusService.updateStatus("ref1", INITIAL, "12345678", statusApproverDetails))

        status(result) shouldBe NO_CONTENT
      }
      "return NO_CONTENT when updating to uploaded, correct information is supplied and current status is initial" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "uploaded"
        )
        when(repository.updateStatusAndApproverDetails(any(), any(), any(), any(), any())).thenReturn(Future.successful(Some(scannedUploadedDetails)))
        val result = await(statusService.updateStatus("ref1", INITIAL, "12345678", statusApproverDetails))

        status(result) shouldBe NO_CONTENT
      }
      "return NO_CONTENT when updating to rejected, correct information is supplied and current status is stored" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "rejected"
        )
        when(repository.updateStatusAndApproverDetails(any(), any(), any(), any(), any())).thenReturn(Future.successful(Some(scannedUploadedDetails)))
        val result = await(statusService.updateStatus("ref1", STORED, "12345678", statusApproverDetails))

        status(result) shouldBe NO_CONTENT
      }
      "return NO_CONTENT when updating to approved, correct information is supplied and current status is stored" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "approved",
          approverName = Some("Approver Name"),
          approverPID = Some("87654321"),
          approverEmail = Some("approver@hmrc.gov.uk")
        )
        when(repository.updateStatusAndApproverDetails(any(), any(), any(), any(), any())).thenReturn(Future.successful(Some(scannedUploadedDetails)))
        val result = await(statusService.updateStatus("ref1", STORED, "12345678", statusApproverDetails))

        status(result) shouldBe NO_CONTENT
      }
      "return BAD_REQUEST when updating to uploadRejected, correct information is supplied but current status is not initial" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "uploadRejected",
          errorCode = Some("error code"),
          errorMessage = Some("error message")
        )
        val result = await(statusService.updateStatus("ref1", SCANNED, "12345678", statusApproverDetails))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe invalidStatusTransitionErrorResponse
      }
      "return BAD_REQUEST when updating to uploadRejected, correct information is supplied but current status is already uploadRejected" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "uploadRejected",
          errorCode = Some("error code"),
          errorMessage = Some("error message")
        )
        val result = await(statusService.updateStatus("ref1", UPLOADREJECTED, "12345678", statusApproverDetails))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe alreadyAtStatusErrorResponse
      }
      "return BAD_REQUEST when updating to uploaded, correct information is supplied but current status is not initial" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "uploaded"
        )
        val result = await(statusService.updateStatus("ref1", STORED, "12345678", statusApproverDetails))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe invalidStatusTransitionErrorResponse
      }
      "return BAD_REQUEST when updating to uploaded, correct information is supplied but current status is already uploaded" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "uploaded"
        )
        val result = await(statusService.updateStatus("ref1", UPLOADED, "12345678", statusApproverDetails))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe alreadyAtStatusErrorResponse
      }
      "return BAD_REQUEST when updating to rejected, correct information is supplied but current status is not stored" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "rejected"
        )
        val result = await(statusService.updateStatus("ref1", INITIAL, "12345678", statusApproverDetails))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe invalidStatusTransitionErrorResponse
      }
      "return BAD_REQUEST when updating to rejected, correct information is supplied but current status is already rejected" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "rejected"
        )
        val result = await(statusService.updateStatus("ref1", REJECTED, "12345678", statusApproverDetails))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe alreadyAtStatusErrorResponse
      }
      "return BAD_REQUEST when updating to approved, correct information is supplied but current status is not stored" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "approved",
          approverName = Some("Approver Name"),
          approverPID = Some("87654321"),
          approverEmail = Some("approver@hmrc.gov.uk")
        )
        val result = await(statusService.updateStatus("ref1", INITIAL, "12345678", statusApproverDetails))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe invalidStatusTransitionErrorResponse
      }
      "return BAD_REQUEST when updating to approved, correct information is supplied but current status is already approved" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "approved",
          approverName = Some("Approver Name"),
          approverPID = Some("87654321"),
          approverEmail = Some("approver@hmrc.gov.uk")
        )
        val result = await(statusService.updateStatus("ref1", APPROVED, "12345678", statusApproverDetails))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe alreadyAtStatusErrorResponse
      }
      "return BAD_REQUEST when status is not recognized" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "weird status"
        )
        val result = await(statusService.updateStatus("ref1", INITIAL, "12345678", statusApproverDetails))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe Json.toJson(ApiErrorResponse("INVALID_STATUS", "Status not supported"))
      }
      "return SERVICE_UNAVAILABLE when fail updating to mongoDB" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "uploadRejected",
          errorCode = Some("error code"),
          errorMessage = Some("error message")
        )
        when(repository.updateStatusAndApproverDetails(any(), any(), any(), any(), any())).thenReturn(Future.successful(None))
        val result = await(statusService.updateStatus("ref1", INITIAL, "12345678", statusApproverDetails))

        status(result) shouldBe SERVICE_UNAVAILABLE
        jsonBodyOf(result) shouldBe serviceUnavailableErrorResponse
      }
    }
    "updateApprovedStatus" must {
      "return NO_CONTENT when correct information is supplied and current status is stored" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "approved",
          approverName = Some("Approver Name"),
          approverPID = Some("87654321"),
          approverEmail = Some("approver@hmrc.gov.uk")
        )
        when(repository.updateStatusAndApproverDetails(any(), any(), any(), any(), any())).thenReturn(Future.successful(Some(scannedUploadedDetails)))
        val result = await(statusService.updateApprovedStatus(STORED, "12345678", statusApproverDetails, "ref1"))

        status(result) shouldBe NO_CONTENT
      }
      "return BAD_REQUEST when approver email is missing" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "approved",
          approverName = Some("Approver Name"),
          approverPID = Some("87654321")
        )
        val result = await(statusService.updateApprovedStatus(SCANNED, "12345678", statusApproverDetails, "ref1"))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe approverMissingFieldErrorResponse
      }
      "return BAD_REQUEST when approver name is missing" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "approved",
          approverPID = Some("87654321"),
          approverEmail = Some("approver@hmrc.gov.uk")
        )
        val result = await(statusService.updateApprovedStatus(SCANNED, "12345678", statusApproverDetails, "ref1"))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe approverMissingFieldErrorResponse
      }
      "return BAD_REQUEST when approver pid is missing" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "approved",
          approverName = Some("Approver Name"),
          approverEmail = Some("approver@hmrc.gov.uk")
        )
        val result = await(statusService.updateApprovedStatus(SCANNED, "12345678", statusApproverDetails, "ref1"))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe approverMissingFieldErrorResponse
      }
      "return BAD_REQUEST when approver name, pid and email are missing" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "approved"
        )
        val result = await(statusService.updateApprovedStatus(STORED, "12345678", statusApproverDetails, "ref1"))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe approverMissingFieldErrorResponse
      }
      "return BAD_REQUEST when approver email is invalid" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "approved",
          approverName = Some("Approver Name"),
          approverPID = Some("87654321"),
          approverEmail = Some("approver\\Tim@hmrc.gov.uk")
        )
        val result = await(statusService.updateApprovedStatus(STORED, "12345678", statusApproverDetails, "ref1"))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe Json.toJson(ApiErrorResponse("INVALID_APPROVER_EMAIL", "Approver email address is invalid"))
      }
      "return BAD_REQUEST when correct information is supplied but current status is scanned" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "approved",
          approverName = Some("Approver Name"),
          approverPID = Some("87654321"),
          approverEmail = Some("approver@hmrc.gov.uk")
        )
        val result = await(statusService.updateApprovedStatus(SCANNED, "12345678", statusApproverDetails, "ref1"))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe invalidStatusTransitionErrorResponse
      }
      "return BAD_REQUEST when approver pid is the same as requestor pid" in {
        val requestorPID = "12345678"
        val statusApproverDetails = StatusApproverDetails(
          status = "approved",
          approverName = Some("Approver Name"),
          approverPID = Some(requestorPID),
          approverEmail = Some("approver_Tim@hmrc.gov.uk")
        )
        val result = await(statusService.updateApprovedStatus(STORED, requestorPID, statusApproverDetails, "ref1"))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe Json.toJson(ApiErrorResponse("INVALID_PID", "Request and approver PIDs cannot be the same"))
      }
    }
    "updateUploadRejectedStatus" must {
      "return NO_CONTENT when correct information is supplied and current status is initiate" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "uploadRejected",
          errorCode = Some("error code"),
          errorMessage = Some("error message")
        )
        when(repository.updateStatusAndApproverDetails(any(), any(), any(), any(), any())).thenReturn(Future.successful(Some(scannedUploadedDetails)))
        val result = await(statusService.updateUploadRejectedStatus(INITIAL, statusApproverDetails, "ref1"))

        status(result) shouldBe NO_CONTENT
      }
      "return BAD_REQUEST when correct information is supplied but current status is stored" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "uploadRejected",
          errorCode = Some("error code"),
          errorMessage = Some("error message")
        )
        val result = await(statusService.updateUploadRejectedStatus(STORED, statusApproverDetails, "ref1"))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe invalidStatusTransitionErrorResponse
      }
      "return BAD_REQUEST when error code is missing" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "uploadRejected",
          errorMessage = Some("error message")
        )
        val result = await(statusService.updateUploadRejectedStatus(INITIAL, statusApproverDetails, "ref1"))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe errorFieldsMissingErrorResponse
      }
      "return BAD_REQUEST when error message is missing" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "uploadRejected",
          errorCode = Some("error code")
        )
        val result = await(statusService.updateUploadRejectedStatus(INITIAL, statusApproverDetails, "ref1"))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe errorFieldsMissingErrorResponse
      }
      "return BAD_REQUEST when error code and message are missing" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "uploadRejected"
        )
        val result = await(statusService.updateUploadRejectedStatus(INITIAL, statusApproverDetails, "ref1"))

        status(result) shouldBe BAD_REQUEST
        jsonBodyOf(result) shouldBe errorFieldsMissingErrorResponse
      }
    }
    "updateStatusToRepo" must {
      "return NO_CONTENT when correct information is supplied" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "approved",
          approverName = Some("Approver Name"),
          approverPID = Some("87654321"),
          approverEmail = Some("approver@hmrc.gov.uk")
        )
        when(repository.updateStatusAndApproverDetails(any(), any(), any(), any(), any())).thenReturn(Future.successful(Some(scannedUploadedDetails)))
        val result = await(statusService.updateStatusToRepo("ref1", statusApproverDetails))

        status(result) shouldBe NO_CONTENT
      }
      "return SERVICE_UNAVAILABLE when correct information is supplied" in {
        val statusApproverDetails = StatusApproverDetails(
          status = "approved",
          approverName = Some("Approver Name"),
          approverPID = Some("87654321"),
          approverEmail = Some("approver@hmrc.gov.uk")
        )
        when(repository.updateStatusAndApproverDetails(any(), any(), any(), any(), any())).thenReturn(Future.successful(None))
        val result = await(statusService.updateStatusToRepo("ref1", statusApproverDetails))

        status(result) shouldBe SERVICE_UNAVAILABLE
        jsonBodyOf(result) shouldBe serviceUnavailableErrorResponse
      }
    }
  }

