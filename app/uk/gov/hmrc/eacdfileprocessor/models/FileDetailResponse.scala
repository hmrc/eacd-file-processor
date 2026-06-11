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

package uk.gov.hmrc.eacdfileprocessor.models

import play.api.libs.json.{Json, OFormat}

import java.time.Instant

case class FileDetailResponse(
                               fileName: String,
                               reference: String,
                               creationDateTime: Instant,
                               errorCode: Option[String],
                               errorMessage: Option[String],
                               fileStatus: String,
                               lastUpdatedDateTime: Instant,
                               requestorEmail: String,
                               requestorPID: String,
                               requestorName: String,
                               downloadUrl: String,
                               fileMimeType: String,
                               uploadTimestamp: Instant,
                               checksum: String,
                               size: Long,
                               failureReason: Option[String],
                               failureMessage: Option[String],
                               approverEmail: Option[String],
                               approverPID: Option[String],
                               approverName: Option[String],
                               approvalDateTime: Option[Instant],
                               totalEntryCount: Int,
                               totalSuccessCount: Int,
                               totalFailureCount: Int
                             )

object FileDetailResponse {
  implicit val format: OFormat[FileDetailResponse] = Json.format[FileDetailResponse]
}