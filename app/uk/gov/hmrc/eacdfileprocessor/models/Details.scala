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

import org.bson.types.ObjectId

import java.net.URL
import java.time.Instant

sealed trait Details

object Details {
  case class UploadedSuccessfully(
                                   name: String,
                                   mimeType: String,
                                   downloadUrl: URL,
                                   size: Option[Long],
                                   checksum: String
                                 ) extends Details

  case class UploadedFailed(
                             failureReason: String,
                             message: String
                           ) extends Details
}

case class UploadedDetails(
                            id: ObjectId,
                            reference: Reference,
                            status: FileStatus,
                            requestorPID: String,
                            requestorEmail: String,
                            requestorName: String,
                            details: Option[Details] = None,
                            approverDetails: Option[ApproverDetails] = None,
                            uploadedDateTime: Option[Instant] = None,
                            lastUpdatedDateTime: Instant = Instant.now(),
                            approvedAtDateTime: Option[Instant] = None,
                          )
