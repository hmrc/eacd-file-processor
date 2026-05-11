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

import org.apache.pekko.stream.Materializer
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.mongodb.scala.SingleObservableFuture
import org.mongodb.scala.model.Filters
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.exceptions.ObjectStoreFileNotFoundException
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport, UnitSpec}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.*
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemMongoRepository, FileRepository}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.objectstore.client.{Md5Hash, Object, ObjectMetadata, Path}

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import scala.concurrent.Future

class FileObjectServiceISpec extends TestSupport with TestData with UnitSpec with Eventually:
  implicit val mat: Materializer = mock[Materializer]
  private val mockAppConfig = mock[AppConfig]
  when(mockAppConfig.timeToLive).thenReturn("3")
  when(mockAppConfig.workItemTimeToLive).thenReturn("270")
  when(mockAppConfig.retryInProgressAfter).thenReturn(30)
  private val objectStoreClient = mock[PlayObjectStoreClient]
  val fileRepository = app.injector.instanceOf[FileRepository]
  private val mongoRepository: MongoComponent = app.injector.instanceOf[MongoComponent]
  private val deEnrolmentWorkItemRepository = new DeEnrolmentWorkItemMongoRepository(mongoRepository, mockAppConfig)

  private val fileObjectService = FileObjectService(mockAppConfig, objectStoreClient, fileRepository, deEnrolmentWorkItemRepository)


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
  }

  private def collectionSize: Long = {
    await(deEnrolmentWorkItemRepository.collection.estimatedDocumentCount().toFuture())
  }

  "FileObjectService" must {
    "getOldestFileFromObjectStore" must {
      "save record details into DeEnrolmentWorkItem when found approved file" in {
        when(objectStoreClient.getObject[String](any(), any())(any(), any())).thenReturn(Future.successful(Some(o)))
        await(fileRepository.createFileRecord(scannedUploadedDetails.copy(status = APPROVED)))
        await(fileObjectService.getOldestFileFromObjectStore)
        eventually {
          collectionSize shouldBe 100
          val uploadedDetails = await(fileRepository.findByReference(scannedUploadedDetails.reference))
          uploadedDetails.get.totalEntryCount shouldBe Some(100)
        }
      }
      "not save record details into DeEnrolmentWorkItem when there is no approved file" in {
        await(fileRepository.createFileRecord(scannedUploadedDetails))
        await(fileObjectService.getOldestFileFromObjectStore)
        collectionSize shouldBe 0
      }
      "throw ObjectStoreFileNotFoundException when can't find file from object store" in {
        when(objectStoreClient.getObject[String](any(), any())(any(), any())).thenReturn(Future.successful(None))
        await(fileRepository.createFileRecord(scannedUploadedDetails.copy(status = APPROVED)))

        val exception = intercept[ObjectStoreFileNotFoundException] {
          await(fileObjectService.getOldestFileFromObjectStore)
        }

        exception.getMessage contains s"No record found for the requested reference: ${scannedUploadedDetails.reference.value} in Object Store" shouldBe true
      }
    }
  }

