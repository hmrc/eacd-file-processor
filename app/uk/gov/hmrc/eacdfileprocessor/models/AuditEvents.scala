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

import play.api.i18n.Messages
import play.api.libs.json.*
import play.api.mvc.Request
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.AuditExtensions
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent

final case class OperatorDetails(authProviderId: String, name: String)
final case class Operator(details: OperatorDetails, role: String)
final case class ServiceIds(service: String, dbIdentifiers: Map[String, String])

trait AuditEvents {

  private val auditSource = "eacd-file-processor"

  private def getTags(hc: HeaderCarrier, transactionName: String, path: String): Map[String, String] = {
    hc.headers(
      Seq(
        "Akamai-Reputation",
        "X-Request-Chain",
        "X-Request-ID",
        "X-Session-ID",
        "clientIP",
        "clientPort",
        "deviceID",
        "x-forwarded-for"
      )
    ).toMap ++ AuditExtensions.auditHeaderCarrier(hc).toAuditTags(transactionName, path)
  }

  private def getDetails(serviceIds: Option[ServiceIds], operator: Operator, extraItems: Seq[(String, JsValue)])
                        (implicit request: Request[_], messages: Messages): JsValue = {
    val serviceDetail = serviceIds.map { ids =>
      Seq(
        "service" -> JsString(Messages(s"service.${ids.service}.name")),
        ids.service -> JsObject(ids.dbIdentifiers.map((k, v) => k -> JsString(v)))
      )
    }.getOrElse(Seq("service" -> JsString("Service not provided")))

    JsObject(
      Seq(
        "operatorPID" -> JsString(operator.details.authProviderId),
        "operatorName" -> JsString(operator.details.name),
        "operatorRole" -> JsString(operator.role),
        "deviceFingerprint" -> JsString(request.headers.get("deviceID").getOrElse(""))
      ) ++ serviceDetail ++ extraItems
    )
  }

  object DownloadFileEvent {
    def apply(path: String, fileReference: String, requesterId: String, requesterName: String, fileName: String, hc: HeaderCarrier)
             (implicit request: Request[_]): ExtendedDataEvent = {

      ExtendedDataEvent(
        auditSource = auditSource,
        auditType = "DownloadFile",
        tags = getTags(hc, "Helpdesk user downloads bulk de-enrolment file", path),
        detail = JsObject(
          Seq(
            "fileReference" -> JsString(fileReference),
            "requesterId" -> JsString(requesterId),
            "requesterName" -> JsString(requesterName),
            "fileName" -> JsString(fileName)
          )
        )
      )
    }
  }
  
}

