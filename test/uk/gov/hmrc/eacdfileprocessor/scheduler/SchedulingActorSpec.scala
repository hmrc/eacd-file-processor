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

import org.apache.pekko.actor.ActorSystem
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{verify, when}
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.eacdfileprocessor.scheduler.SchedulingActor.{DeEnrolmentWorkItemPullMessage, ExpiredFileDeletionMessage, FileStatusUpdateMessage, ProcessApprovedFileMessage}
import uk.gov.hmrc.eacdfileprocessor.services.{ExpiredFileDeletionService, FileStatusUpdateService, LockResponse, ProcessApprovedFileService}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

class SchedulingActorSpec extends TestSupport {

  private def withActorSystem(testCode: ActorSystem => Unit): Unit = {
    val system = ActorSystem("SchedulingActorSpec")
    try testCode(system)
    finally Await.result(system.terminate(), 5.seconds)
  }

  "SchedulingActor" should {

    "invoke ProcessApprovedFileService when ProcessApprovedFileMessage is received" in withActorSystem { system =>
      val actor = system.actorOf(SchedulingActor.props)
      val service = mock[ProcessApprovedFileService]
      val invoked = Promise[Unit]()

      when(service.invoke(using any[ExecutionContext])).thenAnswer { _ =>
        invoked.success(())
        Future.successful(Left(()): Either[Unit, LockResponse])
      }

      actor ! ProcessApprovedFileMessage(service)

      Await.result(invoked.future, 2.seconds)
      verify(service).invoke(using any[ExecutionContext])
    }

    "invoke ScheduledService when DeEnrolmentWorkItemPullMessage is received" in withActorSystem { system =>
      val actor = system.actorOf(SchedulingActor.props)
      val service = mock[ScheduledService[Either[Unit, LockResponse]]]
      val invoked = Promise[Unit]()

      when(service.invoke(using any[ExecutionContext])).thenAnswer { _ =>
        invoked.success(())
        Future.successful(Left(()): Either[Unit, LockResponse])
      }

      actor ! DeEnrolmentWorkItemPullMessage(service)

      Await.result(invoked.future, 2.seconds)
      verify(service).invoke(using any[ExecutionContext])
    }

    "continue handling later messages when a service invocation future fails" in withActorSystem { system =>
      val actor = system.actorOf(SchedulingActor.props)
      val failingService = mock[ScheduledService[Either[Unit, LockResponse]]]
      val succeedingService = mock[ScheduledService[Either[Unit, LockResponse]]]
      val failingInvoked = Promise[Unit]()
      val succeedingInvoked = Promise[Unit]()

      when(failingService.invoke(using any[ExecutionContext])).thenAnswer { _ =>
        failingInvoked.success(())
        Future.failed(new RuntimeException("boom"))
      }
      when(succeedingService.invoke(using any[ExecutionContext])).thenAnswer { _ =>
        succeedingInvoked.success(())
        Future.successful(Left(()): Either[Unit, LockResponse])
      }

      actor ! DeEnrolmentWorkItemPullMessage(failingService)
      actor ! DeEnrolmentWorkItemPullMessage(succeedingService)

      Await.result(failingInvoked.future, 2.seconds)
      Await.result(succeedingInvoked.future, 2.seconds)
      verify(failingService).invoke(using any[ExecutionContext])
      verify(succeedingService).invoke(using any[ExecutionContext])
    }

    "ignore unsupported messages and still process supported scheduled messages" in withActorSystem { system =>
      val actor = system.actorOf(SchedulingActor.props)
      val service = mock[ScheduledService[Either[Unit, LockResponse]]]
      val invoked = Promise[Unit]()

      when(service.invoke(using any[ExecutionContext])).thenAnswer { _ =>
        invoked.success(())
        Future.successful(Left(()): Either[Unit, LockResponse])
      }

      actor ! "unexpected-message"
      actor ! DeEnrolmentWorkItemPullMessage(service)

      Await.result(invoked.future, 2.seconds)
      verify(service).invoke(using any[ExecutionContext])
    }

    "invoke ExpiredFileDeletionService when ExpiredFileDeletionMessage is received" in withActorSystem { system =>
      val actor = system.actorOf(SchedulingActor.props)
      val service = mock[ExpiredFileDeletionService]
      val invoked = Promise[Unit]()

      when(service.invoke(using any[ExecutionContext])).thenAnswer { _ =>
        invoked.success(())
        Future.successful(Left(()): Either[Unit, LockResponse])
      }

      actor ! ExpiredFileDeletionMessage(service)

      Await.result(invoked.future, 2.seconds)
      verify(service).invoke(using any[ExecutionContext])
    }

    "continue handling later expired-file-deletion messages when one invocation future fails" in withActorSystem { system =>
      val actor = system.actorOf(SchedulingActor.props)
      val failingService = mock[ExpiredFileDeletionService]
      val succeedingService = mock[ExpiredFileDeletionService]
      val failingInvoked = Promise[Unit]()
      val succeedingInvoked = Promise[Unit]()

      when(failingService.invoke(using any[ExecutionContext])).thenAnswer { _ =>
        failingInvoked.success(())
        Future.failed(new RuntimeException("boom"))
      }
      when(succeedingService.invoke(using any[ExecutionContext])).thenAnswer { _ =>
        succeedingInvoked.success(())
        Future.successful(Left(()): Either[Unit, LockResponse])
      }

      actor ! ExpiredFileDeletionMessage(failingService)
      actor ! ExpiredFileDeletionMessage(succeedingService)

      Await.result(failingInvoked.future, 2.seconds)
      Await.result(succeedingInvoked.future, 2.seconds)
      verify(failingService).invoke(using any[ExecutionContext])
      verify(succeedingService).invoke(using any[ExecutionContext])
    }
  }

  "invoke FileStatusUpdateService when FileStatusUpdateMessage is received" in withActorSystem { system =>
    val actor = system.actorOf(SchedulingActor.props)
    val service = mock[FileStatusUpdateService]
    val invoked = Promise[Unit]()

    when(service.invoke(using any[ExecutionContext])).thenAnswer { _ =>
      invoked.success(())
      Future.successful(Left(()): Either[Unit, LockResponse])
    }

    actor ! FileStatusUpdateMessage(service)

    Await.result(invoked.future, 2.seconds)
    verify(service).invoke(using any[ExecutionContext])
  }

  "ignore unsupported messages and still process supported file-status-update messages" in withActorSystem { system =>
    val actor = system.actorOf(SchedulingActor.props)
    val service = mock[ScheduledService[Either[Unit, LockResponse]]]
    val invoked = Promise[Unit]()

    when(service.invoke(using any[ExecutionContext])).thenAnswer { _ =>
      invoked.success(())
      Future.successful(Left(()): Either[Unit, LockResponse])
    }

    actor ! 12345 // unsupported message
    actor ! FileStatusUpdateMessage(service)

    Await.result(invoked.future, 2.seconds)
    verify(service).invoke(using any[ExecutionContext])
  }

  "continue handling later file-status-update messages when one invocation future fails" in withActorSystem { system =>
    val actor = system.actorOf(SchedulingActor.props)
    val failingService = mock[ScheduledService[Either[Unit, LockResponse]]]
    val succeedingService = mock[ScheduledService[Either[Unit, LockResponse]]]
    val failingInvoked = Promise[Unit]()
    val succeedingInvoked = Promise[Unit]()

    when(failingService.invoke(using any[ExecutionContext])).thenAnswer { _ =>
      failingInvoked.success(())
      Future.failed(new RuntimeException("boom"))
    }

    when(succeedingService.invoke(using any[ExecutionContext])).thenAnswer { _ =>
      succeedingInvoked.success(())
      Future.successful(Left(()): Either[Unit, LockResponse])
    }

    actor ! FileStatusUpdateMessage(failingService)
    actor ! FileStatusUpdateMessage(succeedingService)

    Await.result(failingInvoked.future, 2.seconds)
    Await.result(succeedingInvoked.future, 2.seconds)

    verify(failingService).invoke(using any[ExecutionContext])
    verify(succeedingService).invoke(using any[ExecutionContext])
  }

  "invoke file-status-update service for each received message" in withActorSystem { system =>
    val actor = system.actorOf(SchedulingActor.props)
    val service = mock[ScheduledService[Either[Unit, LockResponse]]]
    val first = Promise[Unit]()
    val second = Promise[Unit]()
    var count = 0

    when(service.invoke(using any[ExecutionContext])).thenAnswer { _ =>
      count += 1
      if (count == 1) first.success(())
      if (count == 2) second.success(())
      Future.successful(Left(()): Either[Unit, LockResponse])
    }

    actor ! FileStatusUpdateMessage(service)
    actor ! FileStatusUpdateMessage(service)

    Await.result(first.future, 2.seconds)
    Await.result(second.future, 2.seconds)
    verify(service, org.mockito.Mockito.times(2)).invoke(using any[ExecutionContext])
  }

  "handle mixed scheduled message types in one actor instance" in withActorSystem { system =>
    val actor = system.actorOf(SchedulingActor.props)

    val deEnrolService = mock[ScheduledService[Either[Unit, LockResponse]]]
    val fileStatusService = mock[ScheduledService[Either[Unit, LockResponse]]]
    val deEnrolInvoked = Promise[Unit]()
    val fileStatusInvoked = Promise[Unit]()

    when(deEnrolService.invoke(using any[ExecutionContext])).thenAnswer { _ =>
      deEnrolInvoked.success(())
      Future.successful(Left(()): Either[Unit, LockResponse])
    }

    when(fileStatusService.invoke(using any[ExecutionContext])).thenAnswer { _ =>
      fileStatusInvoked.success(())
      Future.successful(Left(()): Either[Unit, LockResponse])
    }

    actor ! DeEnrolmentWorkItemPullMessage(deEnrolService)
    actor ! FileStatusUpdateMessage(fileStatusService)

    Await.result(deEnrolInvoked.future, 2.seconds)
    Await.result(fileStatusInvoked.future, 2.seconds)

    verify(deEnrolService).invoke(using any[ExecutionContext])
    verify(fileStatusService).invoke(using any[ExecutionContext])
  }

}