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

import helper.IntegrationSpec
import org.apache.pekko.stream.Materializer
import org.bson.types.ObjectId
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.mongodb.scala.model.Filters
import org.mongodb.scala.{Document, SingleObservableFuture}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.connectors.EspConnector
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, UnitSpec}
import uk.gov.hmrc.eacdfileprocessor.models.DeEnrolmentWorkItem
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.*
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemMongoRepository, FileRecordValidationErrorRepository, FileRepository}
import uk.gov.hmrc.eacdfileprocessor.utils.DeEnrolmentWorkItemValidator
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem}
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.objectstore.client.{Md5Hash, Object, ObjectMetadata, Path}

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class DeEnrolmentWorkItemSchedulerServiceISpec extends IntegrationSpec with TestData with UnitSpec with Eventually:
  val materializer: Materializer = mock[Materializer]
  val appConfiguration = appConfig
  val executionContext = ec
  val fileRepo = fileRepository
  val MockfileRecordValidationErrorRepository = app.injector.instanceOf[FileRecordValidationErrorRepository]
  private val objectStoreClient = mock[PlayObjectStoreClient]
  val mockLockService = new LockService(lockingRepo)

  private val deEnrolmentWorkItemRepository = new DeEnrolmentWorkItemMongoRepository(mongoRepository, appConfiguration)


  override implicit val ec: ExecutionContext = executionContext
  override implicit val mat: Materializer = materializer
  override implicit val hc: HeaderCarrier = HeaderCarrier()

  private val DeEnrolmentWorkItemSchedulerService = new DeEnrolmentWorkItemSchedulerService(
    appConfig = appConfiguration,
    deEnrolmentWorkItemRepository = deEnrolmentWorkItemRepository,
    fileRecordValidationErrorRepository = MockfileRecordValidationErrorRepository,
    fileRepository = fileRepo,
    espConnector = mock[EspConnector],
    lockService = mockLockService,
    agentServiceCache = mock[AgentServiceCache],
    validator = mock[DeEnrolmentWorkItemValidator]

  )

  val path = java.nio.file.Path.of("it", "test", "resources", "bulk_de_enrolment.csv")
  val content = Files.readString(path, StandardCharsets.UTF_8)
  val o: Object[String] = Object(
    location = Path.Directory(scannedUploadedDetails.reference.value).file("bulk_de_enrolment.csv"),
    content = content,
    metadata = ObjectMetadata(
      contentType = ".csv",
      contentLength = content.length,
      contentMd5 = Md5Hash("somemd5"),
      lastModified = Instant.now(),
      userMetadata = Map.empty
    )
  )



  override def beforeEach(): Unit = {
    await(fileRepository.collection.drop().headOption())
    await(fileRepository.ensureIndexes())
    await(deEnrolmentWorkItemRepository.collection.deleteMany(Filters.exists("_id")).toFuture())
    await(lockingRepo.collection.deleteMany(filter = Document()).toFuture())
  }

  private def collectionSize: Long = {
    await(deEnrolmentWorkItemRepository.collection.estimatedDocumentCount().toFuture())
  }

  "DeEnrolment work item scheduler" must {
    "invoke" must {
      "The action is principal and ES1 returns a 204" in {
        val payload = DeEnrolmentWorkItem(
          reference = scannedUploadedDetails.reference.value,
          recordDetail = "IR-SA~UTR~1234567890,principal",
          creationDateTime = Instant.now()
        )

        val workItem: WorkItem[DeEnrolmentWorkItem] = WorkItem(
          id = ObjectId.get(),
          receivedAt = Instant.now(),
          updatedAt = Instant.now(),
          availableAt = Instant.now(),
          status = ProcessingStatus.InProgress,
          failureCount = 0,
          item = payload
        )


        await(deEnrolmentWorkItemRepository.saveRecordDetails(Seq(workItem), scannedUploadedDetails.reference.value))


        when(objectStoreClient.getObject[String](any(), any())(any(), any())).thenReturn(Future.successful(Some(o)))
        eventually {
        }
      }
      "not save record details into DeEnrolmentWorkItem when there is no approved file" in {
        collectionSize shouldBe 0
      }
    }
  }