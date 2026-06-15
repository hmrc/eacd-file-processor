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
import uk.gov.hmrc.eacdfileprocessor.models.*
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class FileDetailService @Inject()(fileUploadRepo: FileRepository)(implicit ec: ExecutionContext) extends Logging {

  def getFileDetail(reference: String): Future[Option[FileDetailResponse]] =
    fileUploadRepo.findByReference(Reference(reference)).map(_.map(details =>
      FileDetailResponse(
        fileName = details.details.flatMap {
          case details: Details.UploadedSuccessfully => Some(details.name)
          case _ => None
        }.getOrElse(""),
        reference = details.reference.value,
        creationDateTime = details.creationDateTime,
        errorCode = details.details.flatMap {
          case details: Details.UploadedFailed => Some(details.failureReason)
          case _ => None
        },
        errorMessage = details.details.flatMap {
          case details: Details.UploadedFailed => Some(details.message)
          case _ => None
        },
        fileStatus = details.status.value,
        lastUpdatedDateTime = details.lastUpdatedDateTime.getOrElse(details.creationDateTime),
        requestorEmail = details.requestorEmail,
        requestorPID = details.requestorPID,
        requestorName = details.requestorName,
        downloadUrl = details.details.flatMap {
          case details: Details.UploadedSuccessfully => Some(details.downloadUrl.toString)
          case _ => None
        }.getOrElse(""),
        fileMimeType = details.details.flatMap {
          case details: Details.UploadedSuccessfully => Some(details.mimeType)
          case _ => None
        }.getOrElse(""),
        uploadTimestamp = details.uploadedDateTime.getOrElse(details.creationDateTime),
        checksum = details.details.flatMap {
          case details: Details.UploadedSuccessfully => Some(details.checksum)
          case _ => None
        }.getOrElse(""),
        size = details.details.flatMap {
          case details: Details.UploadedSuccessfully => details.size
          case _ => None
        }.getOrElse(0L),
        failureReason = details.details.flatMap {
          case details: Details.UploadedFailed => Some(details.failureReason)
          case _ => None
        },
        failureMessage = details.details.flatMap {
          case details: Details.UploadedFailed => Some(details.message)
          case _ => None
        },
        approverEmail = details.approverDetails.flatMap(_.approverEmail),
        approverPID = details.approverDetails.flatMap(_.approverPID),
        approverName = details.approverDetails.flatMap(_.approverName),
        approvalDateTime = details.approvedAtDateTime,
        totalEntryCount = details.totalEntryCount.getOrElse(0),
        totalSuccessCount = details.totalSuccessCount.getOrElse(0),
        totalFailureCount = details.totalFailureCount.getOrElse(0)
      )
    )
    )
}
