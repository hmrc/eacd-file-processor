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

package uk.gov.hmrc.eacdfileprocessor.scheduler.jobs

import org.apache.pekko.actor.ActorSystem
import play.api.inject.ApplicationLifecycle
import play.api.Configuration
import uk.gov.hmrc.eacdfileprocessor.scheduler.ScheduledJob
import uk.gov.hmrc.eacdfileprocessor.scheduler.SchedulingActor.FileWorkItemPullMessage
import uk.gov.hmrc.eacdfileprocessor.services.FileWorkItemSchedulerService

import javax.inject.Inject
import scala.concurrent.Future

class FileWorkItemPullJob @Inject()(
  val config: Configuration,
  val fileWorkItemSchedulerService: FileWorkItemSchedulerService,
  lifecycle: ApplicationLifecycle
) extends ScheduledJob {

  val jobName: String = "FileWorkItemPullJob"
  val actorSystem: ActorSystem = ActorSystem(jobName)
  val scheduledMessage = FileWorkItemPullMessage(fileWorkItemSchedulerService)

  // Delay schedule initialization until app is fully started to avoid Quartz conflicts in test
  lifecycle.addStopHook(() => Future.successful(()))
  schedule
}


