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

import org.apache.pekko.actor.ActorSystem
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.http.Status.CREATED
import play.api.mvc.*
import play.api.test.Helpers.{DELETE, GET, INTERNAL_SERVER_ERROR, OK, POST, contentAsString, status}
import play.api.test.{DefaultAwaitTimeout, FakeRequest, Helpers}
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.models.auth.AuthRequest
import uk.gov.hmrc.eacdfileprocessor.repository.{FileRecordValidationErrorRepository, FileRepository}
import uk.gov.hmrc.eacdfileprocessor.services.{DeEnrolmentWorkItemSchedulerService, FileStatusUpdateService, ProcessApprovedFileService, UnlockingFailed}
import uk.gov.hmrc.eacdfileprocessor.testOnly.controllers.TestController
import uk.gov.hmrc.http.{Authorization, HeaderCarrier}
import uk.gov.hmrc.internalauth.client.{BackendAuthComponents, Predicate, Retrieval}
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.objectstore.client.{Md5Hash, ObjectSummaryWithMd5, Path}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class TestControllerSpec extends TestSupport with TestData with DefaultAwaitTimeout {

  private val mockRepository = mock[FileRepository]
  private val mockFileRecordValidationErrorRepository = mock[FileRecordValidationErrorRepository]
  private val mockCC: ControllerComponents = Helpers.stubControllerComponents()
  private val mockConfig: play.api.Configuration = mock[play.api.Configuration]
  private val mockAuth: BackendAuthComponents = mock[BackendAuthComponents]
  private val mockObjectStoreClient = mock[PlayObjectStoreClient]
  private val mockProcessApprovedFileService = mock[ProcessApprovedFileService]
  private val mockDeEnrolmentWorkItemSchedulerService = mock[DeEnrolmentWorkItemSchedulerService]
  private val mockFileStatusUpdateService = mock[FileStatusUpdateService]

  implicit lazy val actorSystem: ActorSystem = ActorSystem()

  when(mockConfig.getOptional[Boolean](any())(any())).thenReturn(Some(true))

  class TestTestController extends TestController(
    mockCC,
    mockConfig,
    mockAuth,
    mockObjectStoreClient,
    mockRepository,
    mockFileRecordValidationErrorRepository,
    mockProcessApprovedFileService,
    mockDeEnrolmentWorkItemSchedulerService,
    mockFileStatusUpdateService) {
    override def authorisedEntity(
                                   providedPermission: Predicate,
                                   apiName: String
                                 ): ActionBuilder[AuthRequest, AnyContent] =
      DefaultActionBuilder(mockCC.parsers.defaultBodyParser)(global)
        .andThen(new ActionTransformer[Request, AuthRequest] {
          override protected def executionContext: scala.concurrent.ExecutionContext = global

          override protected def transform[A](request: Request[A]): Future[AuthRequest[A]] =
            Future.successful(
              new AuthRequest(
                request,
                HeaderCarrier(),
                Authorization("Bearer test"),
                Retrieval.Username("testuser")
              )
            )
        })
  }

  val controller = new TestTestController

  "testController#putObject" should {

    "return 201 Created when the object is successfully stored" in {
      when(mockObjectStoreClient.putObject(any(), any(), any(), any(), any(), any())(any(), any()))
        .thenReturn(Future.successful(ObjectSummaryWithMd5(
          location = Path.File("test-ref-123/test-file.csv"),
          contentLength = 0,
          contentMd5 = Md5Hash("md5hash"),
          lastModified = java.time.Instant.now()
        )))

      val result = controller.putObject("test-ref-123", "test-file.csv")(FakeRequest(POST, "/test-only/put-object/test-ref-123/test-file.csv"))

      status(result) shouldBe CREATED
      contentAsString(result) shouldBe "Document stored."
    }

    "return 500 InternalServerError when the object store client throws an exception" in {
      when(mockObjectStoreClient.putObject(any(), any(), any(), any(), any(), any())(any(), any()))
        .thenReturn(Future.failed(new RuntimeException("Unexpected error")))

      val result = controller.putObject("test-ref-123", "test-file.csv")(FakeRequest(POST, "/test-only/put-object/test-ref-123/test-file.csv"))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      contentAsString(result) shouldBe "Error saving the document"
    }
  }

  "TestController#deleteAllObjects" should {

    "return 200 OK when all objects are successfully deleted" in {
      when(mockRepository.dropCollection()).thenReturn(Future.successful(()))
      when(mockFileRecordValidationErrorRepository.dropCollection()).thenReturn(Future.successful(()))

      val result = controller.deleteAllObjects()(FakeRequest(DELETE, "/test-only/delete-all"))

      status(result) shouldBe OK
      contentAsString(result) shouldBe "All test records deleted."
    }

    "return 500 InternalServerError when the repository throws an exception" in {
      when(mockRepository.dropCollection()).thenReturn(Future.failed(new RuntimeException("DB error")))

      val result = controller.deleteAllObjects()(FakeRequest(DELETE, "/test-only/delete-all"))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      contentAsString(result) shouldBe "Error deleting documents"
    }
  }

  "TestController#processApprovedFile" should {

    "return 200 OK when all objects are successfully deleted" in {
      when(mockProcessApprovedFileService.createWorkItemsFromOldestFile).thenReturn(Future.successful(()))

      val result = controller.processApprovedFile(FakeRequest(GET, "/test-only/eacd-file-processor/processApprovedFile"))

      status(result) shouldBe OK
      contentAsString(result) shouldBe "ProcessApprovedFileService invoked successfully."
    }

    "return 500 InternalServerError when the repository throws an exception" in {
      when(mockProcessApprovedFileService.createWorkItemsFromOldestFile).thenReturn(Future.failed(new RuntimeException("Unexpected error")))

      val result = controller.processApprovedFile(FakeRequest(GET, "/test-only/eacd-file-processor/processApprovedFile"))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      contentAsString(result) shouldBe "Error invoking ProcessApprovedFileService"
    }
  }

  "TestController#processDeEnrolmentWorkItems" should {

    "return 200 OK when all objects are successfully deleted" in {
      when(mockDeEnrolmentWorkItemSchedulerService.processBatch).thenReturn(Future.successful(()))

      val result = controller.processDeEnrolmentWorkItems(FakeRequest(GET, "/test-only/eacd-file-processor/processDeEnrolmentWorkItems"))

      status(result) shouldBe OK
      contentAsString(result) shouldBe "DeEnrolmentWorkItemSchedulerService invoked successfully."
    }

    "return 500 InternalServerError when the repository throws an exception" in {
      when(mockDeEnrolmentWorkItemSchedulerService.processBatch).thenReturn(Future.failed(new RuntimeException("Unexpected error")))

      val result = controller.processDeEnrolmentWorkItems(FakeRequest(GET, "/test-only/eacd-file-processor/processDeEnrolmentWorkItems"))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      contentAsString(result) shouldBe "Error invoking DeEnrolmentWorkItemSchedulerService"
    }
  }

  "TestController#updateFileStatus" should {

    "return 200 OK when all objects are successfully deleted" in {
      when(mockFileStatusUpdateService.processProcessingFiles).thenReturn(Future.successful(()))

      val result = controller.updateFileStatus(FakeRequest(GET, "/test-only/eacd-file-processor/updateFileStatus"))

      status(result) shouldBe OK
      contentAsString(result) shouldBe "FileStatusUpdateService invoked successfully."
    }

    "return 500 InternalServerError when the repository throws an exception" in {
      when(mockFileStatusUpdateService.processProcessingFiles).thenReturn(Future.failed(new RuntimeException("Unexpected error")))

      val result = controller.updateFileStatus(FakeRequest(GET, "/test-only/eacd-file-processor/updateFileStatus"))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      contentAsString(result) shouldBe "Error invoking FileStatusUpdateService"
    }
  }
}