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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers.shouldBe
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.http.Status.{NO_CONTENT, OK}
import play.api.mvc.*
import play.api.test.Helpers.{contentAsString, status}
import play.api.test.{DefaultAwaitTimeout, FakeRequest, Helpers}
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.models.auth.AuthRequest
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.http.{Authorization, HeaderCarrier}
import uk.gov.hmrc.internalauth.client.{BackendAuthComponents, Predicate, Retrieval}
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import uk.gov.hmrc.objectstore.client.play.Implicits.*
import uk.gov.hmrc.objectstore.client.{Md5Hash, Object, ObjectMetadata, Path}

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FileControllerSpec extends TestSupport with TestData with DefaultAwaitTimeout {
  private val repository = mock[FileRepository]
  val mockCC: ControllerComponents = Helpers.stubControllerComponents()
  val mockConfig: play.api.Configuration = mock[play.api.Configuration]
  val mockAuth: BackendAuthComponents = mock[BackendAuthComponents]
  val objectStoreClient = mock[PlayObjectStoreClient]

  private implicit lazy val mat: Materializer = app.materializer


  when(mockConfig.getOptional[Boolean](any())(any())).thenReturn(Some(true))



  object TestStatusController extends FileController(repository, mockCC, mockConfig, mockAuth, objectStoreClient) {
    override def authorisedEntity(
                                   providedPermission: Predicate,
                                   apiName: String
                                 ): ActionBuilder[AuthRequest, AnyContent] =
      DefaultActionBuilder(mockCC.parsers.defaultBodyParser)(global)
        .andThen(new ActionTransformer[Request, AuthRequest] {
          override protected def executionContext: scala.concurrent.ExecutionContext = global

          override protected def transform[A](request: Request[A]): Future[AuthRequest[A]] =
            scala.concurrent.Future.successful(
              new AuthRequest(
                request,
                HeaderCarrier(),
                Authorization("Bearer test"),
                Retrieval.Username("testuser")
              )
            )
        })
  }

  "FileController" should {
    "return the file content when correctly information is supplied" in {
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

      when(repository.getNameOfFile(any())).thenReturn(Future.successful(Some("test.csv")))
      when(objectStoreClient.getObject[Source[ByteString, NotUsed]](any(), any())(any(), any()))
        .thenReturn(Future.successful(Some(o)))
      val request = FakeRequest(routes.FileController.getFile("test-ref"))
      val result = TestStatusController.getFile("test-ref")(request)
      status(result) shouldBe OK
      contentAsString(result) shouldBe "file content"
    }

    "return NO_CONTENT when no item is found in object store" in {
      when(repository.getNameOfFile(any())).thenReturn(Future.successful(Some("test.csv")))
      when(objectStoreClient.getObject[Source[ByteString, NotUsed]](any(), any())(any(), any()))
        .thenReturn(Future.successful(None))
      val request = FakeRequest(routes.FileController.getFile("test-ref"))
      val result = TestStatusController.getFile("test-ref")(request)
      status(result) shouldBe NO_CONTENT
    }

    "return NO_CONTENT when file reference is not found" in {
      when(repository.getNameOfFile(any())).thenReturn(Future.successful(None))
      val request = FakeRequest(routes.FileController.getFile("test-ref"))
      val result = TestStatusController.getFile("test-ref")(request)
      status(result) shouldBe NO_CONTENT
    }

  }
}
