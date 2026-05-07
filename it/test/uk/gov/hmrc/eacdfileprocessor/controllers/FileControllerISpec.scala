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

package uk.gov.hmrc.eacdfileprocessor.controllers

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.http.Status.{BAD_REQUEST, NO_CONTENT, OK, UNSUPPORTED_MEDIA_TYPE}
import play.api.libs.json.Json
import play.api.test.Helpers.{GET, PUT, await, contentAsJson, route, status, writeableOf_AnyContentAsJson, writeableOf_AnyContentAsText}
import play.api.test.{DefaultAwaitTimeout, FakeRequest}
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.*
import uk.gov.hmrc.eacdfileprocessor.models.Reference
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import play.api.test.Helpers.writeableOf_AnyContentAsEmpty
import uk.gov.hmrc.objectstore.client.{Md5Hash, Object, ObjectMetadata, Path}
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient

import java.time.Instant
import scala.concurrent.Future

class FileControllerISpec extends TestSupport with TestData with DefaultAwaitTimeout:
  lazy val repository = app.injector.instanceOf[FileRepository]
  val objectStoreClient = mock[PlayObjectStoreClient]
  val reference = "08aad019-7f68-4456-8d52-93f12109876f"

  val bytes = ByteString.fromString("file content")
  val o: Object[Source[ByteString, NotUsed]] = Object(
    location = Path.Directory("some").file("location"),
    content = Source.single(bytes),
    metadata = ObjectMetadata(
      contentType = ".csv",
      contentLength = bytes.length,
      contentMd5 = Md5Hash("somemd5"),
      lastModified = Instant.now(),
      userMetadata = Map.empty
    )
  )

  override def beforeEach(): Unit = {
    await(repository.collection.drop().headOption())
    await(repository.ensureIndexes())
  }

  "GET /file:reference (integration)" should {
    "return 200 when retrieving a file from object store" in {
      when(objectStoreClient.getObject[Source[ByteString, NotUsed]](any(), any())(any(), any()))
        .thenReturn(Future.successful(Some(o)))


      val request = FakeRequest(GET, routes.FileController.getFile(reference).url)
        .withHeaders("Authorization" -> "Bearer test-token")
      for {
        _ <- repository.createFileRecord(scannedUploadedDetails.copy(reference = Reference(reference)))
        result <- route(app, request).get
        uploadedFileDetails <- repository.findByReference(Reference(reference))
      } yield {
        status(Future(result)) shouldBe OK
      }
    }
    "return 204 when file is not found in object store" in {
      when(objectStoreClient.getObject[Source[ByteString, NotUsed]](any(), any())(any(), any()))
        .thenReturn(Future.successful(None))

      val request = FakeRequest(GET, routes.FileController.getFile(reference).url)
        .withHeaders("Authorization" -> "Bearer test-token")
      for {
        _ <- repository.createFileRecord(scannedUploadedDetails.copy(reference = Reference(reference)))
        result <- route(app, request).get
      } yield {
        status(Future(result)) shouldBe NO_CONTENT
      }
    }

    "return 204 when file reference is not found" in {
      val request = FakeRequest(GET, routes.FileController.getFile(reference).url)
        .withHeaders("Authorization" -> "Bearer test-token")
      for {
        result <- route(app, request).get
      } yield {
        status(Future(result)) shouldBe NO_CONTENT
      }
    }

  }
