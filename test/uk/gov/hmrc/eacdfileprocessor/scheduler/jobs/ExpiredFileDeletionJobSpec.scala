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
import uk.gov.hmrc.eacdfileprocessor.services.ExpiredFileDeletionService

class ExpiredFileDeletionJobSpec extends TestSupport {

  "ExpiredFileDeletionJob" should {

    "initialize with expected defaults" in {
      val service = mock[ExpiredFileDeletionService]
      val actorSystem = ActorSystem("ExpiredFileDeletionJobSpec-1")
      val config = Configuration.from(Map("schedules.ExpiredFileDeletionJob.enabled" -> false))

      val job = ExpiredFileDeletionJob(config, service, actorSystem)

      job.jobName shouldBe "ExpiredFileDeletionJob"
      job.expiredFileDeletionService shouldBe service
      job.actorSystem shouldBe actorSystem
      job.expression shouldBe None

      await(CoordinatedShutdown(actorSystem).run(CoordinatedShutdown.UnknownReason))
    }

    "read expression when configured" in {
      val service = mock[ExpiredFileDeletionService]
      val actorSystem = ActorSystem("ExpiredFileDeletionJobSpec-2")
      val config = Configuration.from(
        Map(
          "schedules.ExpiredFileDeletionJob.enabled" -> false,
          "schedules.ExpiredFileDeletionJob.expression" -> "0_0_2_?_*_*"
        )
      )

      val job = ExpiredFileDeletionJob(config, service, actorSystem)

      job.expression shouldBe Some("0_0_2_?_*_*")

      await(CoordinatedShutdown(actorSystem).run(CoordinatedShutdown.UnknownReason))
    }
  }
}