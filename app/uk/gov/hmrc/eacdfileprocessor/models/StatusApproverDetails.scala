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

import play.api.libs.json.{Json, OFormat, Reads, Writes}

case class StatusApproverDetails(status: String,
                                 approverEmail: Option[String] = None,
                                 approverPID: Option[String] = None,
                                 approverName: Option[String] = None,
                                 errorCode: Option[String] = None,
                                 errorMessage: Option[String] = None
                 )

object StatusApproverDetails {
  implicit val format: OFormat[StatusApproverDetails] = Json.format[StatusApproverDetails]
}

enum FileStatus(val value: String):
  case INITIATE extends FileStatus("initiate")
  case SCANNED extends FileStatus("scanned")
  case FAILED extends FileStatus("failed")
  case STORED extends FileStatus("stored")
  case UPLOAD_REJECTED extends FileStatus("uploadRejected")
  case UPLOADED extends FileStatus("uploaded")
  case REJECTED extends FileStatus("rejected")
  case APPROVED extends FileStatus("approved")