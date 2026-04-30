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

case class StatusDetailsModel(
                               reference: String,
                               requestorEmail: String,
                               requestorPID: String,
                               requestorName: String,
                               fileName: Option[String],
                               fileStatus: String,
                               creationDateTime: Option[Instant]
                             )

object StatusDetailsModel {
  implicit val format: OFormat[StatusDetailsModel] = Json.format[StatusDetailsModel]
}
