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

import play.api.Logging
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.Results.{BadRequest, NoContent, ServiceUnavailable}
import play.api.mvc.{Request, Result}
import uk.gov.hmrc.eacdfileprocessor.connectors.EmailConnector
import uk.gov.hmrc.eacdfileprocessor.models.*
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.*
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.eacdfileprocessor.utils.ValidationUtil
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditConnector

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

@Singleton
class EmailService @Inject()(emailConnector: EmailConnector)(implicit ec: ExecutionContext, hc: HeaderCarrier) {

  def sendFileFailEmail(uploadDetails: UploadedDetails, failedCallbackBody: FailedCallbackBody): Future[Boolean] = {
    emailConnector.sendFileFailedEmail(
      requestorName = uploadDetails.requestorName,
      fileName = uploadDetails.details.map(Details.getFileName).getOrElse(""),
      to = uploadDetails.requestorEmail,
      uploadedDateTime = uploadDetails.uploadedDateTime.getOrElse(
        throw new RuntimeException(s"Upload date time not found for reference: ${uploadDetails.reference.value}")),
      reference = uploadDetails.reference.value,
      failureReason = failedCallbackBody.failureDetails.failureReason,
      failureMessage = failedCallbackBody.failureDetails.message,
      templateId = "emac_helpdesk_bulk_deenrolment_file_upload_failure"
    )
  }

  def sendUpdateFileStatusEmail(uploadedDetails: UploadedDetails): Future[Boolean] = {
    val approverDetails = uploadedDetails.approverDetails.getOrElse(
      throw new RuntimeException(s"Approver details not found for file reference: ${uploadedDetails.reference.value}"))
    emailConnector.sendUpdateFileStatusEmail(
      requestorName = uploadedDetails.requestorName,
      fileName = uploadedDetails.details.map(Details.getFileName).getOrElse(""),
      to = uploadedDetails.requestorEmail,
      uploadedDateTime = uploadedDetails.uploadedDateTime.getOrElse(
        throw new RuntimeException(s"Upload date time not found for reference: ${uploadedDetails.reference.value}")),
      reference = uploadedDetails.reference.value,
      approverName = approverDetails.approverName.getOrElse(""),
      approverEmail = approverDetails.approverEmail.getOrElse(""),
      templateId = if uploadedDetails.status == APPROVED then "emac_helpdesk_bulk_deenrolment_file_approved" else "emac_helpdesk_bulk_deenrolment_file_rejected_by_approver"
    )
  }
}
