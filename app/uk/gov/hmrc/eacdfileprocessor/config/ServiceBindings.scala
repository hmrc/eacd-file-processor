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

package uk.gov.hmrc.eacdfileprocessor.config

import com.typesafe.config.Config
import play.Environment as JEnvironment
import play.inject.Module.bindClass
import play.inject.{Binding, Module}
import uk.gov.hmrc.eacdfileprocessor.connectors.{EmailConnector, EmailConnectorImpl}
import uk.gov.hmrc.eacdfileprocessor.controllers.{CallbackController, FileController, InitiateFileStorageController, StatusController}
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemMongoRepository, DeEnrolmentWorkItemRepository, FileRepository, LockingRepository}
import uk.gov.hmrc.eacdfileprocessor.scheduler.jobs.{DeEnrolmentWorkItemPullJob, ExpiredFileDeletionJob, ProcessApprovedFileJob}
import uk.gov.hmrc.eacdfileprocessor.services.*
import uk.gov.hmrc.eacdfileprocessor.utils.DeEnrolmentWorkItemValidator

import java.util
import scala.jdk.CollectionConverters.*

class ServiceBindings extends Module {
  override def bindings(environment: JEnvironment, configuration: Config): util.List[Binding[?]] =
    (
      bindConfigure() ++
        bindServices() ++
        bindControllers() ++
        bindRepositories() ++
        bindSchedulers() ++
        bindConnector()
      ).asJava

  private def bindConfigure(): Seq[Binding[?]] = Seq(
    bindClass(classOf[AppConfig]).toSelf.eagerly()
  )
  
  private def bindConnector(): Seq[Binding[?]] = Seq(
      bindClass(classOf[EmailConnector]).to(classOf[EmailConnectorImpl]).eagerly()
  )

  private def bindServices(): Seq[Binding[?]] = Seq(
      bindClass(classOf[LockService]).toSelf.eagerly(),
      bindClass(classOf[DeEnrolmentWorkItemValidator]).toSelf.eagerly(),
      bindClass(classOf[ProcessApprovedFileService]).to(classOf[DefaultProcessApprovedFileService]).eagerly()
    )

  private def bindControllers(): Seq[Binding[?]] = Seq(
    bindClass(classOf[CallbackController]).toSelf.eagerly(),
    bindClass(classOf[FileController]).toSelf.eagerly(),
    bindClass(classOf[InitiateFileStorageController]).toSelf.eagerly(),
    bindClass(classOf[StatusController]).toSelf.eagerly()
  )

  private def bindRepositories(): Seq[Binding[?]] = Seq(
    bindClass(classOf[FileRepository]).toSelf.eagerly(),
    bindClass(classOf[LockingRepository]).toSelf.eagerly(),
    bindClass(classOf[DeEnrolmentWorkItemRepository]).to(classOf[DeEnrolmentWorkItemMongoRepository]).eagerly()
  )

  private def bindSchedulers(): Seq[Binding[?]] = Seq(
    bindClass(classOf[ProcessApprovedFileJob]).toSelf.eagerly(),
    bindClass(classOf[DeEnrolmentWorkItemPullJob]).toSelf.eagerly(),
    bindClass(classOf[ExpiredFileDeletionJob]).toSelf.eagerly()
  )
}
