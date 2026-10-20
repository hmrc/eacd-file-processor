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

import play.api.Logging
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemRepository, FileRepository}
import uk.gov.hmrc.eacdfileprocessor.scheduler.ScheduledService
import uk.gov.hmrc.eacdfileprocessor.utils.MetricsReporter

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

@Singleton
class DashboardMetricsService @Inject()(files: FileRepository,
                                        workItems: DeEnrolmentWorkItemRepository,
                                        metrics: MetricsReporter) extends ScheduledService[Unit] with Logging {

  // Reuse the support endpoint's repository aggregation, including its expiry window.
  // A failed read leaves the previous gauge values intact and surfaces the failure.
  override def invoke(using ExecutionContext): Future[Unit] =
    (for {
      fileStatusCounts <- files.getFileStatusCounts
      _ = metrics.reportFileStatusCounts(fileStatusCounts)
      workItemCounts <- workItems.countPendingItems.zip(workItems.countCompletedItems)
      _ = metrics.reportWorkItemCounts(workItemCounts._1, workItemCounts._2)
    } yield ()
      ).andThen { case Failure(e) => logger.error("Failed to refresh file status metrics", e) }
}
