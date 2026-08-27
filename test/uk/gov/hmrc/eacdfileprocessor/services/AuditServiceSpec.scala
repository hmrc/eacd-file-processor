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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalactic.Prettifier.default
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport, UnitSpec}
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}

import scala.concurrent.Future

class AuditServiceSpec extends TestSupport with TestData with UnitSpec:
  private lazy val mockAuditConnector = mock[AuditConnector]
  private val auditService = AuditService(mockAuditConnector)

  "AuditService" must {
    "auditFileFailEvent" must {
      "return correct AuditResult for file fail event" in {
        when(mockAuditConnector.sendExtendedEvent(any())(any(), any())).thenReturn(Future.successful(AuditResult.Success))

        val result = await(auditService.auditFileFailEvent(initiateUploadDetails, failedFileDetails))

        result shouldBe AuditResult.Success
      }
    }
    "auditDownloadFileEvent" must {
      "return correct AuditResult for download file event" in {
        when(mockAuditConnector.sendExtendedEvent(any())(any(), any())).thenReturn(Future.successful(AuditResult.Success))

        val result = await(auditService.auditDownloadFileEvent(initiateUploadDetails, "fileName.csv"))

        result shouldBe AuditResult.Success
      }
    }
    "auditUpdateFileStatusEvent" must {
      "return correct AuditResult for update file status event" in {
        when(mockAuditConnector.sendExtendedEvent(any())(any(), any())).thenReturn(Future.successful(AuditResult.Success))

        val result = await(auditService.auditUpdateFileStatusEvent(initiateUploadDetails.copy(approverDetails = Some(approverDetails))))

        result shouldBe AuditResult.Success
      }
      "throw exception when approver details missing for update file status audit event" in {
        val exception = intercept[RuntimeException] {
          await(auditService.auditUpdateFileStatusEvent(initiateUploadDetails))
        }

        exception.getMessage contains "Approver details not found for file reference" shouldBe true
      }
    }
  }

