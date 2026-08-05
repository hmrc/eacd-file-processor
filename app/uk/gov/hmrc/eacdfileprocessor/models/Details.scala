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
import play.api.libs.functional.syntax.*
import play.api.libs.json.{Format, JsNull, JsObject, JsString, JsValue, Json, OFormat, Reads, Writes, __}


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

  def getFileName(details: Details): String =
    details match {
      case UploadedSuccessfully(name, _, _, _, _) => name
      case UploadedFailed(_, _) => ""
    }
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
                            totalEntryCount: Option[Int] = None,
                            uploadedDateTime: Option[Instant] = None,
                            lastUpdatedDateTime: Option[Instant] = None,
                            approvedAtDateTime: Option[Instant] = None,
                            creationDateTime: Instant = Instant.now(),
                            totalFailureCount: Option[Int] = None,
                            totalSuccessCount: Option[Int] = None
                          )

object UploadedDetails {
  given Writes[UploadedDetails] = (d: UploadedDetails) => {
    given Writes[URL] = Writes(url => JsString(url.toString))
    given Writes[Details.UploadedSuccessfully] = Json.writes[Details.UploadedSuccessfully]
    given Writes[Details.UploadedFailed] = Json.writes[Details.UploadedFailed]
    given Writes[Details] = Writes {
      case f: Details.UploadedFailed       => Json.toJson(f).as[JsObject]
      case s: Details.UploadedSuccessfully => Json.toJson(s).as[JsObject]
    }
    Json.obj(
      "id"                  -> d.id.toHexString,
      "reference"           -> d.reference.value,
      "status"              -> d.status.value,
      "requestorPID"        -> d.requestorPID,
      "requestorEmail"      -> d.requestorEmail,
      "requestorName"       -> d.requestorName,
      "details"             -> d.details,
      "approverDetails"     -> d.approverDetails,
      "totalEntryCount"     -> d.totalEntryCount,
      "uploadedDateTime"    -> d.uploadedDateTime.fold[JsValue](JsNull)(i => JsString(i.toString)),
      "lastUpdatedDateTime" -> d.lastUpdatedDateTime.fold[JsValue](JsNull)(i => JsString(i.toString)),
      "approvedAtDateTime"  -> d.approvedAtDateTime.map(_.toString),
      "creationDateTime"    -> d.creationDateTime.toString,
      "totalFailureCount"   -> d.totalFailureCount,
      "totalSuccessCount"   -> d.totalSuccessCount
    )
  }
}

case class FileStatusCount(status: String, count: Int)

object FileStatusCount {
  given Format[FileStatusCount] = {
    val read: Reads[FileStatusCount] =
      ((__ \ "_id").format[String]
        ~ (__ \ "count").format[Int]
        )(FileStatusCount.apply, Tuple.fromProductTyped _)

    val write: Writes[FileStatusCount] = (statusCount: FileStatusCount) => Json.format[FileStatusCount].writes(statusCount)

    Format(read, write)
  }
}