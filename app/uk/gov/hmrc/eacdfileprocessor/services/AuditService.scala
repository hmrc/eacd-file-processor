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

import play.api.mvc.Request
import uk.gov.hmrc.eacdfileprocessor.controllers.routes
import uk.gov.hmrc.eacdfileprocessor.models.*
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.APPROVED
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AuditService @Inject()(auditConnector: AuditConnector)(implicit ec: ExecutionContext, hc: HeaderCarrier, request: Request[_])
  extends AuditEvents {

  def auditFileFailEvent(uploadDetails: UploadedDetails, failedCallbackBody: FailedCallbackBody): Future[AuditResult] = {
    auditConnector.sendExtendedEvent(
      EmailEvent(
        fileReference = uploadDetails.reference.value,
        requestorId = uploadDetails.requestorPID,
        requestorName = uploadDetails.requestorName,
        failureReason = failedCallbackBody.failureDetails.failureReason,
        failureMessage = failedCallbackBody.failureDetails.message,
        emailAlertSentTo = uploadDetails.requestorEmail,
        hc = hc
      )
    )
  }

  def auditDownloadFileEvent(uploadDetails: UploadedDetails, fileName: String): Future[AuditResult] = {
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

  def auditUpdateFileStatusEvent(uploadDetails: UploadedDetails): Future[AuditResult] = {
    val approverDetails = uploadDetails.approverDetails.getOrElse(
      throw new RuntimeException(s"Approver details not found for file reference: ${uploadDetails.reference.value}"))
    auditConnector.sendExtendedEvent(
      UpdateFileStatusEvent(
        fileReference = uploadDetails.reference.value,
        requesterId = uploadDetails.requestorPID,
        requesterName = uploadDetails.requestorName,
        approvalId = approverDetails.approverPID.getOrElse(""),
        approvalName = approverDetails.approverName.getOrElse(""),
        fileName = uploadDetails.details.map(Details.getFileName).getOrElse(""),
        isFileApproved = if uploadDetails.status == APPROVED then true else false,
        emailAlertSentTo = uploadDetails.requestorEmail,
        hc = hc
      )
    )
  }
}
