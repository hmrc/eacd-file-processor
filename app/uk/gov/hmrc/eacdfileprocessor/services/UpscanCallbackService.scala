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

import uk.gov.hmrc.eacdfileprocessor.models.upscan.{CallbackBody, FailedCallbackBody, ReadyCallbackBody, Details}
import uk.gov.hmrc.http.BadRequestException

import javax.inject.Inject
import scala.concurrent.Future

class UpscanCallbackService @Inject() (sessionStorage: UploadProgressTracker) {
  private val allowedMimeTypes: Set[String] = Set(
    "application/pdf",
    "text/csv", // .csv
  )

  def handleCallback(callback: CallbackBody): Future[Unit] =

    val uploadStatus =
      callback match
        case s: ReadyCallbackBody =>
          if (allowedMimeTypes.contains(s.uploadDetails.fileMimeType)) {
            Details.UploadedSuccessfully(
              name = s.uploadDetails.fileName,
              mimeType = s.uploadDetails.fileMimeType,
              downloadUrl = s.downloadUrl,
              size = Some(s.uploadDetails.size),
              checksum = s.uploadDetails.checksum
            )
          } else {
            throw new BadRequestException(s"Incorrect file type uploaded, preferred file type was: ${s.uploadDetails.fileMimeType}")
          }
        case f: FailedCallbackBody =>
          Details.UploadedFailed(failureReason = f.failureDetails.failureReason,
            message = f.failureDetails.message)

    sessionStorage.registerUploadResult(callback.reference, uploadStatus)
}
