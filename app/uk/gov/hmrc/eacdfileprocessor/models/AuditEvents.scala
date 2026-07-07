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

import play.api.libs.json.*
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.AuditExtensions
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent

trait AuditEvents {

  private val auditSource = "eacd-file-processor"


  private def getDetails(fileReference: String, requestorId: String, requestorName: String, extraItems: Seq[(String, JsValue)]): JsValue = {

    JsObject(
      Seq(
        "fileReference" -> JsString(fileReference),
        "requesterId" -> JsString(requestorId),
        "requesterName" -> JsString(requestorName)
      ) ++ extraItems
    )
  }

  object FileFailEvent {
    def apply(fileReference: String, requestorId: String, requestorName: String, failureReason: String, failureMessage: String,
              emailAlertSentTo: String, hc: HeaderCarrier): ExtendedDataEvent = {

      ExtendedDataEvent(
        auditSource = auditSource,
        auditType = "FileFailed",
        tags = AuditExtensions.auditHeaderCarrier(hc).toAuditTags(),
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

  object FileScannedEvent {
    def apply(fileReference: String, requestorId: String, requestorName: String, fileName: String, fileSize: String,
              emailAlertSentTo: String, hc: HeaderCarrier): ExtendedDataEvent = {

      ExtendedDataEvent(
        auditSource = auditSource,
        auditType = "FileScanned",
        tags = AuditExtensions.auditHeaderCarrier(hc).toAuditTags(),
        detail = getDetails(
          fileReference,
          requestorId,
          requestorName,
          Seq(
            "fileName" -> JsString(fileName),
            "fileSize" -> JsString(fileSize),
            "emailAlertSentTo" -> JsString(emailAlertSentTo)
          )
        )
      )
    }
  }

  object DownloadFileEvent {
    def apply(fileReference: String, requesterId: String, requesterName: String, fileName: String, hc: HeaderCarrier): ExtendedDataEvent = {

      ExtendedDataEvent(
        auditSource = auditSource,
        auditType = "DownloadFile",
        tags = AuditExtensions.auditHeaderCarrier(hc).toAuditTags(),
        detail = getDetails(
          fileReference,
          requesterId,
          requesterName,
          Seq("fileName" -> JsString(fileName))
        )
      )
    }
  }

  object UpdateFileStatusEvent {
    def apply(fileReference: String, requesterId: String, requesterName: String, approvalId: String, approvalName: String,
              fileName: String, isFileApproved: Boolean, emailAlertSentTo: String, hc: HeaderCarrier): ExtendedDataEvent = {

      ExtendedDataEvent(
        auditSource = auditSource,
        auditType = "UpdateFileStatus",
        tags = AuditExtensions.auditHeaderCarrier(hc).toAuditTags(),
        detail = getDetails(
          fileReference,
          requesterId,
          requesterName,
          Seq(
            "approvalId" -> JsString(approvalId),
            "approvalName" -> JsString(approvalName),
            "fileName" -> JsString(fileName),
            "fileApproved" -> JsBoolean(isFileApproved),
            "emailAlertSentTo" -> JsString(emailAlertSentTo)
          )
        )
      )
    }
  }

}