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

import org.apache.pekko.actor.{Actor, ActorLogging, Props}
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.hmrc.eacdfileprocessor.scheduler.SchedulingActor.ScheduledMessage
import uk.gov.hmrc.eacdfileprocessor.services.{LockResponse, ProcessApprovedFileService, UpdateFileStatusService}
import uk.gov.hmrc.eacdfileprocessor.utils.ScheduledService

class SchedulingActor extends Actor with ActorLogging {
  import context.dispatcher
  
  private[scheduler] val logger: Logger = LoggerFactory.getLogger(getClass)
  
  override def receive: Receive = {
    case message: ScheduledMessage[_] =>
      logger.info(s"Received ${message.getClass.getSimpleName}")
      message.service.invoke
  }
}

object SchedulingActor {
  sealed trait ScheduledMessage[A] {
    val service: ScheduledService[A]
  }

  case class ProcessApprovedFileMessage(service: ProcessApprovedFileService) extends ScheduledMessage[Either[Unit, LockResponse]]

  case class DeEnrolmentWorkItemPullMessage(service: ScheduledService[Either[Unit, LockResponse]]) extends ScheduledMessage[Either[Unit, LockResponse]]

  case class UpdateFileStatusMessage(service: UpdateFileStatusService) extends ScheduledMessage[Either[Unit, LockResponse]]

  def props: Props = Props(classOf[SchedulingActor])
}
