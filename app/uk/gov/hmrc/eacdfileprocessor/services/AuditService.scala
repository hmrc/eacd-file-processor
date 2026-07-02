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

import uk.gov.hmrc.eacdfileprocessor.models.*
import uk.gov.hmrc.eacdfileprocessor.models.Details.UploadedSuccessfully
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.APPROVED
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AuditService @Inject()(val auditConnector: AuditConnector)(implicit ec: ExecutionContext)
  extends AuditEvents {

  def auditFileFailEvent(uploadedDetails: UploadedDetails, failureDetails: Details.UploadedFailed)(implicit hc: HeaderCarrier): Future[AuditResult] = {
    auditConnector.sendExtendedEvent(
      FileFailEvent(
        fileReference = uploadedDetails.reference.value,
        requestorId = uploadedDetails.requestorPID,
        requestorName = uploadedDetails.requestorName,
        failureReason = failureDetails.failureReason,
        failureMessage = failureDetails.message,
        emailAlertSentTo = uploadedDetails.requestorEmail,
        hc = hc
      )
    )
  }
  
  def auditFileScannedEvent(uploadedDetails: UploadedDetails, successfulDetails: UploadedSuccessfully)(implicit hc: HeaderCarrier): Future[AuditResult] = {
    auditConnector.sendExtendedEvent(
      FileScannedEvent(
        fileReference = uploadedDetails.reference.value,
        requestorId = uploadedDetails.requestorPID,
        requestorName = uploadedDetails.requestorName,
        fileName = successfulDetails.name,
        fileSize = successfulDetails.size.fold("Unknown")(_.toString),
        emailAlertSentTo = uploadedDetails.requestorEmail,
        hc = hc
      )
    )
  }

  def auditDownloadFileEvent(uploadDetails: UploadedDetails, fileName: String)(implicit hc: HeaderCarrier): Future[AuditResult] = {
    auditConnector.sendExtendedEvent(
      DownloadFileEvent(
        fileReference = uploadDetails.reference.value,
        requesterId = uploadDetails.requestorPID,
        requesterName = uploadDetails.requestorName,
        fileName = fileName,
        hc = hc
      )
    )
  }

  def auditUpdateFileStatusEvent(uploadDetails: UploadedDetails)(implicit hc: HeaderCarrier): Future[AuditResult] = {
    val approverDetails = uploadDetails.approverDetails.getOrElse(
      throw new RuntimeException(s"Approver details not found for file reference: ${uploadDetails.reference.value}"))
    auditConnector.sendExtendedEvent(
      UpdateFileStatusEvent(
        fileReference = uploadDetails.reference.value,
        requesterId = uploadDetails.requestorPID,
        requesterName = uploadDetails.requestorName.trim,
        approvalId = approverDetails.approverPID.getOrElse(""),
        approvalName = approverDetails.approverName.getOrElse("").trim,
        fileName = uploadDetails.details.map(Details.getFileName).getOrElse(""),
        isFileApproved = if uploadDetails.status == APPROVED then true else false,
        emailAlertSentTo = uploadDetails.requestorEmail,
        hc = hc
      )
    )
  }
}
