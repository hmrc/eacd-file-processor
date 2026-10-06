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

import org.mockito.Mockito.{verify, when}
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.eacdfileprocessor.models.FileStatusCount
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.eacdfileprocessor.utils.MetricsReporter

import scala.concurrent.{ExecutionContext, Future}

class DashboardMetricsServiceSpec extends TestSupport {
  given ExecutionContext = ExecutionContext.global

  "DashboardMetricsService" should {
    "refresh file status counts from the repository" in {
      val files = mock[FileRepository]
      val metrics = mock[MetricsReporter]
      val counts = Seq(FileStatusCount("approved", 2))
      when(files.getFileStatusCounts).thenReturn(Future.successful(counts))

      await(new DashboardMetricsService(files, metrics).invoke) shouldBe ()
      verify(metrics).reportFileStatusCounts(counts)
    }

    "surface a failed snapshot rather than publishing stale counts" in {
      val files = mock[FileRepository]
      val metrics = mock[MetricsReporter]
      when(files.getFileStatusCounts).thenReturn(Future.failed(new RuntimeException("database unavailable")))

      intercept[RuntimeException] {
        await(new DashboardMetricsService(files, metrics).invoke)
      }.getMessage shouldBe "database unavailable"
      verify(metrics, org.mockito.Mockito.never()).reportFileStatusCounts(Seq.empty)
    }
  }
}
