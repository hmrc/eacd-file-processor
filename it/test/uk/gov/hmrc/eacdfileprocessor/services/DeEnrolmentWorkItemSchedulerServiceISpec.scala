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
import org.mockito.Mockito
import org.mockito.Mockito.when
import org.mongodb.scala.model.Filters
import org.mongodb.scala.{Document, SingleObservableFuture}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.http.Status.{NO_CONTENT, OK}
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.connectors.EspConnector
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, UnitSpec}
import uk.gov.hmrc.eacdfileprocessor.models.DeEnrolmentWorkItem
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.*
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemMongoRepository, FileRecordValidationErrorRepository, FileRepository}
import uk.gov.hmrc.eacdfileprocessor.utils.DeEnrolmentWorkItemValidator
import uk.gov.hmrc.http.client.{HttpClientV2, RequestBuilder}
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse}
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
  lazy val mockHttpClientV2: HttpClientV2 = Mockito.mock(classOf[HttpClientV2])
  val mockRequestBuilder: RequestBuilder = Mockito.mock(classOf[RequestBuilder])
  val MockfileRecordValidationErrorRepository = app.injector.instanceOf[FileRecordValidationErrorRepository]
  private val objectStoreClient = mock[PlayObjectStoreClient]
  val mockLockService = new LockService(lockingRepo)

  private val mockDeEnrolmentWorkItemRepository = new DeEnrolmentWorkItemMongoRepository(mongoRepository, appConfiguration)


  private val deEnrolmentWorkItemSchedulerService = new DeEnrolmentWorkItemSchedulerService {
    override val appConfig = appConfiguration
    override val deEnrolmentWorkItemRepository = mockDeEnrolmentWorkItemRepository
    override val fileRecordValidationErrorRepository = MockfileRecordValidationErrorRepository
    override val fileRepository = fileRepo
    override val espConnector = mock[EspConnector]
    override val lockService = mockLockService
    override val agentServiceCache = mock[AgentServiceCache]
    override val validator = mock[DeEnrolmentWorkItemValidator]
    override implicit val ec: ExecutionContext = executionContext
    override implicit val hc: HeaderCarrier = HeaderCarrier()

  }

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

        when(mockHttpClientV2.get(any())(any())).thenReturn(mockRequestBuilder)
        when(mockRequestBuilder.execute(any[HttpReads[HttpResponse]], any()))
          .thenReturn(Future.successful(HttpResponse(OK, body =
            """
              |{
              |  "serviceNames": [
              |    " IR-SA ",
              |    "IR-SA",
              |    " ",
              |    "VAT"
              |  ]
              |}
              |""".stripMargin)))

        when(mockHttpClientV2.get(any())(any())).thenReturn(mockRequestBuilder)
        when(mockRequestBuilder.execute(any[HttpReads[HttpResponse]], any()))
          .thenReturn(Future.successful(HttpResponse(NO_CONTENT, body = "")))

        await(deEnrolmentWorkItemRepository.saveRecordDetails(deEnrolmentWorkItems, scannedUploadedDetails.reference.value))
        await(fileRepository.createFileRecord(scannedUploadedDetails))

        when(objectStoreClient.getObject[String](any(), any())(any(), any())).thenReturn(Future.successful(Some(o)))
        await(deEnrolmentWorkItemSchedulerService.invoke)
        eventually {
         val successCount = await(fileRepository.findByReference(scannedUploadedDetails.reference)).get.totalSuccessCount
          successCount shouldBe Some(1)
        }
      }
    }
  }