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
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.models.Details.{UploadedFailed, UploadedSuccessfully}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.{FAILED, SCANNED, STORED}
import uk.gov.hmrc.eacdfileprocessor.models.{Details, Reference, UploadedDetails}
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.http.{Authorization, HeaderCarrier, StringContextOps}
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.objectstore.client.{Path, RetentionPeriod, Sha256Checksum}

import java.net.URL
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@Singleton
class UploadProgressTracker @Inject()(repository: FileRepository,
                                      appConfig: AppConfig,
                                      osClient: PlayObjectStoreClient,
                                      val auditService: AuditService,
                                      emailService: EmailService)(implicit ec: ExecutionContext) extends Logging {


  def getUploadResult(reference: Reference): Future[Option[UploadedDetails]] =
    repository.findByReference(reference)

  def registerUploadResult(fileReference: Reference, details: Details)(implicit hc: HeaderCarrier): Future[Unit] =
    for {
      status <- details match {
        case f: Details.UploadedFailed => Future(FAILED)
        case s: Details.UploadedSuccessfully => Future(SCANNED)
      }
      _ <- repository.updateStatusAndDetails(fileReference, status, details).map {
        case Some(_) if status == SCANNED =>
          val uploadedDetails = details.asInstanceOf[UploadedSuccessfully]
          (for {
            uploadDetails <- repository.findByReference(fileReference).map {
              case Some(details) => details
              case None => throw new RuntimeException("Upload details not found for reference: " + fileReference.value)
            }
            _ <- auditConnector.sendExtendedEvent(
              EmailEventScanned(
                fileReference = fileReference.value,
                requestorId = uploadDetails.requestorPID,
                requestorName = uploadDetails.requestorName,
                fileName = uploadedDetails.name,
                fileSize = uploadedDetails.size.fold("Unknown")(_.toString),
                emailAlertSentTo = uploadDetails.requestorEmail,
                hc = hc
              )
            )
            _ <- emailConnector.sendSuccessEmail(
              requestorName = uploadDetails.requestorName,
              fileName = uploadedDetails.name,
              uploadDateTime = uploadDetails.uploadedDateTime.getOrElse(uploadDetails.creationDateTime),
              to = uploadDetails.requestorEmail,
              reference = fileReference.value,
              fileExpiryDays = appConfig.fileExpiryDays.toString,
              templateId = "emac_helpdesk_bulk_deenrolment_file_upload_scan_success"
            )
          } yield ()).recover {
            case ex => logger.error(s"issue occurred when sending email and audit ${ex.getMessage}")
          }
          transferToObjectStore(downloadUrl = uploadedDetails.downloadUrl,
            mimeType = uploadedDetails.mimeType,
            checksum = uploadedDetails.checksum,
            fileName = uploadedDetails.name,
            fileReference = fileReference)
        case Some(uploadedDetails) if status == FAILED =>
          auditService.auditFileFailEvent(uploadedDetails, details.asInstanceOf[UploadedFailed])
          emailService.sendFileFailEmail(uploadedDetails, details.asInstanceOf[UploadedFailed])
        case _ =>
          Future.unit
      }
    } yield
      ()

  private[services] def transferToObjectStore(
                                               downloadUrl: URL,
                                               mimeType: String,
                                               checksum: String,
                                               fileName: String,
                                               fileReference: Reference
                                             )(implicit hc: HeaderCarrier): Future[Unit] = {
    val fileLocation = Path.File(s"${fileReference.value}/$fileName")
    val contentSha256 = Sha256Checksum.fromHex(checksum)
    osClient
      .uploadFromUrl(
        from = url"$downloadUrl",
        to = fileLocation,
        retentionPeriod = RetentionPeriod.SixMonths,
        contentType = Some(mimeType),
        contentSha256 = Some(contentSha256)
      )(hc.copy(authorization = Some(Authorization(appConfig.internalAuthToken))))
      .transformWith {
        case Failure(exception) =>
          logger.warn(s"FAILED_OBJECT_STORE_UPDATE Failed upload file to object store for reference: ${fileReference.value} $exception")
          Future.unit
        case Success(objectWithMD5) =>
          repository.updateStatus(fileReference, STORED).map {
            case None =>
              logger.warn(s"Could not update file status for reference: ${fileReference.value}")
            case _ => ()
          }
      }
  }
}
