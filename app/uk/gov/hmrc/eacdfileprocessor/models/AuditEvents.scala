/*
 * Copyright 2023 HM Revenue & Customs
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

import play.api.Configuration
import play.api.i18n.Messages
import play.api.libs.json.*
import play.api.mvc.Request
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.AuditExtensions
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent

trait AuditEvents {

  private val auditSource = "eacd-file-processor"

  private def getTags(hc: HeaderCarrier): Map[String, String] = {
    hc.headers(
      Seq(
        "Akamai-Reputation",
        "X-Request-Chain",
        "X-Request-ID",
        "X-Session-ID",
        "clientIP",
        "clientPort",
        "deviceID"
      )
    ).toMap
  }


  private def getDownloadTags(hc: HeaderCarrier, path: Option[String] = None): Map[String, String] = {
    hc.headers(
      Seq(
        "clientIP",
        "X-Request-ID",
        "deviceID",
        "clientPort",
        "X-Request-Chain",
        "X-Session-ID",
      )
    ).toMap ++ AuditExtensions.auditHeaderCarrier(hc).toAuditTags(
      path.getOrElse("")
    )
  }


  private def getDetails(fileReference: String, requestorId: String, requestorName: String, extraItems: Seq[(String, JsValue)])
                        (implicit request: Request[_]): JsValue = {


    JsObject(
      Seq(
        "fileReference" -> JsString(fileReference),
        "requesterId" -> JsString(requestorId),
        "requesterName" -> JsString(requestorName)
      )
        ++ Seq(
      ) ++ extraItems
    )
  }
  
  private def getDownloadDetails(fileReference: String, requestorId: String, requestorName: String, fileName: String)
                        (implicit request: Request[_]): JsValue = {


    JsObject(
      Seq(
        "fileReference" -> JsString(fileReference),
        "requesterId" -> JsString(requestorId),
        "requesterName" -> JsString(requestorName),
        "fileName" -> JsString(fileName)
      )
    )
  }
  
  object EmailEvent {
    def apply(fileReference: String, requestorId: String, requestorName: String, failureReason: String, failureMessage: String,
              emailAlertSentTo: String, hc: HeaderCarrier)
             (implicit request: Request[_]): ExtendedDataEvent = {

      ExtendedDataEvent(
        auditSource = auditSource,
        auditType = "FileFailed",
        tags = getTags(hc),
        detail = getDetails(
          fileReference,
          requestorId,
          requestorName,
          Seq(
            "failureReason" -> JsString(failureReason),
            "failureMessage" -> JsString(failureMessage),
            "emailAlertSentTo" -> JsString(emailAlertSentTo)
          )
        )
      )
    }

  }

  object DownloadFileEvent {
    def apply(path: String, fileReference: String, requesterId: String, requesterName: String, fileName: String, hc: HeaderCarrier)
             (implicit request: Request[_]): ExtendedDataEvent = {

      ExtendedDataEvent(
        auditSource = auditSource,
        auditType = "DownloadFile",
        tags = getDownloadTags(hc, Some(path)),
          detail = getDownloadDetails(
          fileReference,
          requesterId,
          requesterName,
          fileName
        )
      )
    }
  }

}