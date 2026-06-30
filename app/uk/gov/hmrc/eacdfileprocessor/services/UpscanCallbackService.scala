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
import play.api.mvc.Request
import uk.gov.hmrc.eacdfileprocessor.models.*
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UpscanCallbackService @Inject()(callbackStorage: UploadProgressTracker) extends Logging {

  def handleCallback(callback: CallbackBody)(implicit ex: ExecutionContext, hc: HeaderCarrier, request: Request[_]): Future[Unit] = {
    val uploadStatus: Option[Details] = callback match {
      case s: ReadyCallbackBody =>
        Some(Details.UploadedSuccessfully(
          name = s.uploadDetails.fileName,
          mimeType = s.uploadDetails.fileMimeType,
          downloadUrl = s.downloadUrl,
          size = Some(s.uploadDetails.size),
          checksum = s.uploadDetails.checksum
        ))
      case f: FailedCallbackBody =>
        Some(Details.UploadedFailed(
          failureReason = f.failureDetails.failureReason,
          message = f.failureDetails.message
        ))
    }

    uploadStatus match {
      case Some(status) => callbackStorage.registerUploadResult(callback.reference, status)
      case None => Future.unit
    }
  }
}
