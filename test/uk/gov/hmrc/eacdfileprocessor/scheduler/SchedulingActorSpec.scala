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

package uk.gov.hmrc.eacdfileprocessor.scheduler

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestActorRef, TestKit}
import org.mockito.Mockito.mock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j
import play.api.LoggerLike
import uk.gov.hmrc.eacdfileprocessor.helper.AssertionHelpers
import uk.gov.hmrc.eacdfileprocessor.scheduler.SchedulingActor.ProcessApprovedFileMessage
import uk.gov.hmrc.eacdfileprocessor.services.ProcessApprovedFileService

class SchedulingActorSpec extends TestKit(
  ActorSystem("SchedulingActorSpec", ConfigFactory.parseString(
    """
  akka {
    loggers = ["akka.testkit.TestEventListener"]
  }
"""))) with AnyWordSpecLike with BeforeAndAfterAll with AssertionHelpers {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  "A Scheduling actor" should {
    val actorRef = TestActorRef(new SchedulingActor)
    val actor = actorRef.underlyingActor

    val loggerLike: LoggerLike = new LoggerLike {
      override val logger: slf4j.Logger = actor.logger
    }

    "be able to receive a message" when {
      Seq(
        ProcessApprovedFileMessage(mock(classOf[ProcessApprovedFileService]))
      ) foreach { message =>
        s"there is a ${message.getClass.getSimpleName}" in {
          withCaptureOfLoggingFrom(loggerLike) { logs =>
            actorRef ! message
            logs.exists(event => event.getMessage.contains(message.getClass.getSimpleName))
          }
        }
      }
    }
  }

}