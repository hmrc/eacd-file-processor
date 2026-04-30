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
import uk.gov.hmrc.eacdfileprocessor.connectors.EmailConnector
import uk.gov.hmrc.eacdfileprocessor.models.{AuditEvents, CallbackBody, Details, FailedCallbackBody, ReadyCallbackBody}
import uk.gov.hmrc.http.{BadRequestException, HeaderCarrier}
import uk.gov.hmrc.play.audit.http.connector.AuditConnector

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UpscanCallbackService @Inject()(sessionStorage: UploadProgressTracker,
                                      val auditConnector: AuditConnector,
                                      emailConnector: EmailConnector) extends AuditEvents {
  private val allowedMimeTypes: Set[String] = Set(
    "text/csv" // .csv
  )

  def handleCallback(callback: CallbackBody)(implicit ex: ExecutionContext, hc: HeaderCarrier, request: Request[_]): Future[Unit] = {
    val uploadStatus: Option[Details] = callback match {
      case s: ReadyCallbackBody if allowedMimeTypes.contains(s.uploadDetails.fileMimeType) =>
        Some(Details.UploadedSuccessfully(
          name = s.uploadDetails.fileName,
          mimeType = s.uploadDetails.fileMimeType,
          downloadUrl = s.downloadUrl,
          size = Some(s.uploadDetails.size),
          checksum = s.uploadDetails.checksum
        ))
      case f: FailedCallbackBody =>
        for {
          uploadDetails <- sessionStorage.getUploadResult(callback.reference).map {
            case Some(details) => details
            case None => throw new RuntimeException("Upload details not found for reference: " + callback.reference)
          }
          _ <- auditConnector.sendExtendedEvent(
            EmailEvent(
              fileReference = uploadDetails.reference.value,
              requestorId = uploadDetails.requestorPID,
              requestorName = uploadDetails.requestorName,
              failureReason = f.failureDetails.failureReason,
              failureMessage = f.failureDetails.message,
              emailAlertSentTo = uploadDetails.requestorEmail,
              hc = hc
            )
          )
          _ <- emailConnector.sendEmail(
            requestorName = uploadDetails.requestorName,
            fileName = uploadDetails.details.map {
              case Details.UploadedSuccessfully(name, _, _, _, _) => name
              case _ => ""
            }.getOrElse(""),
            to = uploadDetails.requestorEmail,
            uploadDateTime = uploadDetails.uploadedDateTime.getOrElse(throw new RuntimeException("Upload date time not found for reference: " + callback.reference)),
            reference = uploadDetails.reference.value,
            failureReason = f.failureDetails.failureReason,
            failureMessage = f.failureDetails.message,
            templateId = "emac_helpdesk_bulk_deenrolment_file_upload_failure"
          )
        } yield ()

        Some(Details.UploadedFailed(
          failureReason = f.failureDetails.failureReason,
          message = f.failureDetails.message
        ))
        
      case _ => None
    }

    uploadStatus match {
      case Some(status) => sessionStorage.registerUploadResult(callback.reference, status)
      case None => Future(throw new BadRequestException("Incorrect file type uploaded, preferred file type was: text/csv"))
    }
  }
}
