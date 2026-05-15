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

package uk.gov.hmrc.eacdfileprocessor

import play.api.{Application, Configuration, Logging}
import uk.gov.hmrc.eacdfileprocessor.scheduler.jobs.FileWorkItemPullJob

import javax.inject.Inject

/**
 * Starts the FileWorkItemPullJob scheduler after the Play application is fully initialized.
 * This avoids Quartz scheduler conflicts when tests run multiple test app instances.
 */
class SchedulerStartup @Inject()(app: Application) extends Logging {
  private lazy val config: Configuration = app.configuration

  // Only start scheduler if enabled and not in test mode
  private val schedulerEnabled = config.getOptional[Boolean]("schedules.FileWorkItemPullJob.enabled").getOrElse(false)

  if (schedulerEnabled) {
    try {
      app.injector.instanceOf[FileWorkItemPullJob]
      logger.info("FileWorkItemPullJob scheduler started")
    } catch {
      case e: Exception =>
        logger.error("Failed to start FileWorkItemPullJob scheduler", e)
    }
  } else {
    logger.info("FileWorkItemPullJob scheduler is disabled")
  }
}


