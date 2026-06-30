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

import uk.gov.hmrc.eacdfileprocessor.connectors.EmailConnector
import uk.gov.hmrc.eacdfileprocessor.models.*
import uk.gov.hmrc.eacdfileprocessor.models.Details.UploadedSuccessfully
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.*
import uk.gov.hmrc.http.HeaderCarrier

import java.time.Instant.now
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class EmailService @Inject()(emailConnector: EmailConnector)(implicit ec: ExecutionContext) {

  def sendFileFailEmail(uploadDetails: UploadedDetails, failureDetails: Details.UploadedFailed)(implicit hc: HeaderCarrier): Future[Boolean] = {
    val params = Map(
      "requestorName" -> uploadDetails.requestorName,
      "fileName" -> uploadDetails.details.map(Details.getFileName).getOrElse(""),
      "uploadedDateTime" -> getUploadedDateTime(uploadDetails),
      "reference" -> uploadDetails.reference.value,
      "failureReason" -> failureDetails.failureReason,
      "failureMessage" -> failureDetails.message
    )

    emailConnector.sendEmail(params, uploadDetails.requestorEmail, "emac_helpdesk_bulk_deenrolment_file_upload_failure")
  }

  def sendFileScannedEmail(uploadedDetails: UploadedDetails, successfulDetails: UploadedSuccessfully, fileExpiryDays: String)(implicit hc: HeaderCarrier): Future[Boolean] = {
    val params = Map(
      "requestorName" -> uploadedDetails.requestorName,
      "fileName" -> successfulDetails.name,
      //When upscan is stubbed and callback gets called before uploaded, uploadedDateTime can be empty
      "uploadedDateTime" -> uploadedDetails.uploadedDateTime.map(_.toString).getOrElse(now().toString),
      "reference" -> uploadedDetails.reference.value,
      "fileExpiryDays" -> fileExpiryDays
    )

    emailConnector.sendEmail(params, uploadedDetails.requestorEmail, "emac_helpdesk_bulk_deenrolment_file_upload_scan_success")
  }

  def sendUpdateFileStatusEmail(uploadedDetails: UploadedDetails)(implicit hc: HeaderCarrier): Future[Boolean] = {
    val approverDetails = uploadedDetails.approverDetails.getOrElse(
      throw new RuntimeException(s"Approver details not found for file reference: ${uploadedDetails.reference.value}"))
    val params = Map(
      "requestorName" -> uploadedDetails.requestorName,
      "fileName" -> uploadedDetails.details.map(Details.getFileName).getOrElse(""),
      "uploadedDateTime" -> getUploadedDateTime(uploadedDetails),
      "approverName" -> approverDetails.approverName.getOrElse(""),
      "approverEmail" -> approverDetails.approverEmail.getOrElse(""),
      "reference" -> uploadedDetails.reference.value
    )
    val templateId = if uploadedDetails.status == APPROVED then
      "emac_helpdesk_bulk_deenrolment_file_approved"
    else
      "emac_helpdesk_bulk_deenrolment_file_rejected_by_approver"
    emailConnector.sendEmail(params, uploadedDetails.requestorEmail, templateId)
  }

  private def getUploadedDateTime(uploadedDetails: UploadedDetails): String =
    uploadedDetails.uploadedDateTime.map(_.toString).getOrElse(
      throw new RuntimeException(s"Uploaded date time not found for reference: ${uploadedDetails.reference.value}"))
}
