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

import org.apache.pekko.actor.{ActorSystem, CoordinatedShutdown}
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.Configuration
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.eacdfileprocessor.services.ProcessApprovedFileService

class ProcessApprovedFileJobSpec extends TestSupport {

  "ProcessApprovedFileJob" should {

    "initialize with expected defaults" in {
      val service = mock[ProcessApprovedFileService]
      val actorSystem = ActorSystem("ProcessApprovedFileJobSpec-1")
      val config = Configuration.from(Map("schedules.ProcessApprovedFileJob.enabled" -> false))

      val job = ProcessApprovedFileJob(config, service, actorSystem)

      job.jobName shouldBe "ProcessApprovedFileJob"
      job.processApprovedFilesService shouldBe service
      job.actorSystem shouldBe actorSystem
      job.expression shouldBe None

      await(CoordinatedShutdown(actorSystem).run(CoordinatedShutdown.UnknownReason))
    }

    "read expression when configured" in {
      val service = mock[ProcessApprovedFileService]
      val actorSystem = ActorSystem("ProcessApprovedFileJobSpec-2")
      val config = Configuration.from(
        Map(
          "schedules.ProcessApprovedFileJob.enabled" -> false,
          "schedules.ProcessApprovedFileJob.expression" -> "0_*/15_*_?_*_*"
        )
      )

      val job = ProcessApprovedFileJob(config, service, actorSystem)

      job.expression shouldBe Some("0_*/15_*_?_*_*")

      await(CoordinatedShutdown(actorSystem).run(CoordinatedShutdown.UnknownReason))
    }
  }
}