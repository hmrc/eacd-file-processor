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

package uk.gov.hmrc.eacdfileprocessor.models.upscan

import play.api.libs.json.*
import uk.gov.hmrc.eacdfileprocessor.models.*
import uk.gov.hmrc.eacdfileprocessor.utils.HttpUrlFormat

import java.net.URL
import java.time.Instant

sealed trait CallbackBody:
  def reference: Reference

case class ReadyCallbackBody(
                              reference    : Reference,
                              downloadUrl  : URL,
                              uploadDetails: UploadDetails
                            ) extends CallbackBody

case class FailedCallbackBody(
                               reference     : Reference,
                               failureDetails: ErrorDetails
                             ) extends CallbackBody

object CallbackBody:

  given Reads[UploadDetails]      = Json.reads[UploadDetails]
  given Reads[ErrorDetails]       = Json.reads[ErrorDetails]

  given Reads[ReadyCallbackBody]  =
    given Format[URL] = HttpUrlFormat.format
    Json.reads[ReadyCallbackBody]

  given Reads[FailedCallbackBody] = Json.reads[FailedCallbackBody]

  given Reads[CallbackBody] =
    (json: JsValue) =>
      json \ "fileStatus" match
        case JsDefined(JsString("READY"))  => json.validate[ReadyCallbackBody]
        case JsDefined(JsString("FAILED")) => json.validate[FailedCallbackBody]
        case JsDefined(value)              => JsError(s"Invalid type discriminator: $value")
        case _                             => JsError(s"Missing type discriminator")

case class UploadDetails(
                          uploadTimestamp: Instant,
                          checksum       : String,
                          fileMimeType   : String,
                          fileName       : String,
                          size           : Long
                        )

case class ErrorDetails(
                         failureReason: String,
                         message      : String
                       )
