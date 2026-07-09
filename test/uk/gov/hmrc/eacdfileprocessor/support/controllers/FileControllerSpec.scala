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

package uk.gov.hmrc.eacdfileprocessor.support.controllers

import org.bson.types.ObjectId
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.mvc.*
import play.api.test.Helpers.*
import play.api.test.{DefaultAwaitTimeout, FakeRequest, Helpers}
import uk.gov.hmrc.eacdfileprocessor.models.auth.AuthRequest
import uk.gov.hmrc.eacdfileprocessor.models.{FileRecordValidationError, Reference}
import uk.gov.hmrc.eacdfileprocessor.repository.FileRecordValidationErrorRepository
import uk.gov.hmrc.eacdfileprocessor.services.AuditService
import uk.gov.hmrc.http.{Authorization, HeaderCarrier}
import uk.gov.hmrc.internalauth.client.{BackendAuthComponents, Predicate, Retrieval}
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FileControllerSpec extends AnyWordSpec with Matchers with MockitoSugar with DefaultAwaitTimeout {

  private val mockFileRecordValidationErrorRepository = mock[FileRecordValidationErrorRepository]
  private val mockCC: ControllerComponents = Helpers.stubControllerComponents()
  private val mockConfig: play.api.Configuration = mock[play.api.Configuration]
  private val mockAuth: BackendAuthComponents = mock[BackendAuthComponents]
  private val mockAuditService = mock[AuditService]
  private val mockObjectStoreClient = mock[PlayObjectStoreClient]

  when(mockConfig.getOptional[Boolean](any())(any())).thenReturn(Some(true))

  object TestFileController extends FileController(
    mockFileRecordValidationErrorRepository,
    mockCC,
    mockConfig,
    mockAuth,
    mockAuditService,
    mockObjectStoreClient
  ) {
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
    "getFileErrors" when {
      val testReference = "REF123"
      val testReference2 = "REF456"

      "authorization" should {
        "use correct permissions for EMAC support" in {
          TestFileController.emacSupportPermission.resource.resourceType.value shouldBe "eacd-file-processor"
          TestFileController.emacSupportPermission.resource.resourceLocation.value shouldBe "file"
          TestFileController.emacSupportPermission.action.value shouldBe "ADMIN"
        }

        "use correct permissions for helpdesk" in {
          TestFileController.helpdeskPermission.resource.resourceType.value shouldBe "services-enrolments-helpdesk-frontend"
          TestFileController.helpdeskPermission.resource.resourceLocation.value shouldBe "file"
          TestFileController.helpdeskPermission.action.value shouldBe "ADMIN"
        }
      }

      "no validation errors exist" should {
        "return NoContent (204)" in {
          when(mockFileRecordValidationErrorRepository.findByReference(Reference(testReference)))
            .thenReturn(Future.successful(Seq()))

          val request = FakeRequest("GET", s"/file/errors/$testReference")
          val result = TestFileController.getFileErrors(testReference).apply(request)

          status(result) shouldBe NO_CONTENT
        }
      }

      "validation errors exist" should {
        "return 200 OK with CSV content" in {
          val errors = Seq(
            FileRecordValidationError(
              new ObjectId(),
              Reference(testReference),
              "file1.csv",
              "record1",
              "Invalid format",
              Instant.now()
            )
          )

          when(mockFileRecordValidationErrorRepository.findByReference(Reference(testReference)))
            .thenReturn(Future.successful(errors))

          val request = FakeRequest("GET", s"/file/errors/$testReference")
          val result = TestFileController.getFileErrors(testReference).apply(request)

          status(result) shouldBe OK
          contentType(result) shouldBe Some("text/csv")
          header("Content-Disposition", result) shouldBe Some(s"""attachment; filename="file-errors-$testReference.csv"""")
        }

        "return CSV with proper header" in {
          val errors = Seq(
            FileRecordValidationError(
              new ObjectId(),
              Reference(testReference),
              "file1.csv",
              "record1",
              "Invalid format",
              Instant.now()
            )
          )

          when(mockFileRecordValidationErrorRepository.findByReference(Reference(testReference)))
            .thenReturn(Future.successful(errors))

          val request = FakeRequest("GET", s"/file/errors/$testReference")
          val result = TestFileController.getFileErrors(testReference).apply(request)

          val csvContent = contentAsString(result)
          csvContent should startWith("reference,fileName,recordDetail,errorMessage,creationDateTime")
        }

        "include all error fields in CSV" in {
          val instant = Instant.parse("2024-01-01T12:00:00Z")
          val errors = Seq(
            FileRecordValidationError(
              new ObjectId(),
              Reference(testReference),
              "file1.csv",
              "record1",
              "Invalid format",
              instant
            )
          )

          when(mockFileRecordValidationErrorRepository.findByReference(Reference(testReference)))
            .thenReturn(Future.successful(errors))

          val request = FakeRequest("GET", s"/file/errors/$testReference")
          val result = TestFileController.getFileErrors(testReference).apply(request)

          val csvContent = contentAsString(result)
          csvContent should include("REF123")
          csvContent should include("file1.csv")
          csvContent should include("record1")
          csvContent should include("Invalid format")
        }

        "escape commas in field values" in {
          val errors = Seq(
            FileRecordValidationError(
              new ObjectId(),
              Reference(testReference),
              "file1.csv",
              "record1,with,commas",
              "Error, with, commas",
              Instant.now()
            )
          )

          when(mockFileRecordValidationErrorRepository.findByReference(Reference(testReference)))
            .thenReturn(Future.successful(errors))

          val request = FakeRequest("GET", s"/file/errors/$testReference")
          val result = TestFileController.getFileErrors(testReference).apply(request)

          val csvContent = contentAsString(result)
          csvContent should include("\"record1,with,commas\"")
          csvContent should include("\"Error, with, commas\"")
        }

        "escape quotes in field values" in {
          val errors = Seq(
            FileRecordValidationError(
              new ObjectId(),
              Reference(testReference),
              "file1.csv",
              """record"with"quotes""",
              """Error"with"quotes""",
              Instant.now()
            )
          )

          when(mockFileRecordValidationErrorRepository.findByReference(Reference(testReference)))
            .thenReturn(Future.successful(errors))

          val request = FakeRequest("GET", s"/file/errors/$testReference")
          val result = TestFileController.getFileErrors(testReference).apply(request)

          val csvContent = contentAsString(result)
          csvContent should include(""""record""with""quotes"""")
          csvContent should include(""""Error""with""quotes"""")
        }

        "escape newlines in field values" in {
          val errors = Seq(
            FileRecordValidationError(
              new ObjectId(),
              Reference(testReference),
              "file1.csv",
              "record\nwith\nnewlines",
              "Error\nwith\nnewlines",
              Instant.now()
            )
          )

          when(mockFileRecordValidationErrorRepository.findByReference(Reference(testReference)))
            .thenReturn(Future.successful(errors))

          val request = FakeRequest("GET", s"/file/errors/$testReference")
          val result = TestFileController.getFileErrors(testReference).apply(request)

          val csvContent = contentAsString(result)
          csvContent should include("\"record\nwith\nnewlines\"")
          csvContent should include("\"Error\nwith\nnewlines\"")
        }

        "escape carriage returns in field values" in {
          val errors = Seq(
            FileRecordValidationError(
              new ObjectId(),
              Reference(testReference),
              "file1.csv",
              "record\rcarriage",
              "Error\rreturn",
              Instant.now()
            )
          )

          when(mockFileRecordValidationErrorRepository.findByReference(Reference(testReference)))
            .thenReturn(Future.successful(errors))

          val request = FakeRequest("GET", s"/file/errors/$testReference")
          val result = TestFileController.getFileErrors(testReference).apply(request)

          val csvContent = contentAsString(result)
          csvContent should include("\"record\rcarriage\"")
          csvContent should include("\"Error\rreturn\"")
        }

        "handle multiple errors" in {
          val errors = Seq(
            FileRecordValidationError(new ObjectId(), Reference(testReference), "file1.csv", "record1", "Error 1", Instant.now()),
            FileRecordValidationError(new ObjectId(), Reference(testReference), "file1.csv", "record2", "Error 2", Instant.now()),
            FileRecordValidationError(new ObjectId(), Reference(testReference), "file1.csv", "record3", "Error 3", Instant.now())
          )

          when(mockFileRecordValidationErrorRepository.findByReference(Reference(testReference)))
            .thenReturn(Future.successful(errors))

          val request = FakeRequest("GET", s"/file/errors/$testReference")
          val result = TestFileController.getFileErrors(testReference).apply(request)

          val csvContent = contentAsString(result)
          val lines = csvContent.split("\n")
          lines.length shouldBe 4 // header + 3 rows
        }

        "set correct filename with reference" in {
          val errors = Seq(
            FileRecordValidationError(new ObjectId(), Reference(testReference2), "file1.csv", "record1", "Error", Instant.now())
          )

          when(mockFileRecordValidationErrorRepository.findByReference(Reference(testReference2)))
            .thenReturn(Future.successful(errors))

          val request = FakeRequest("GET", s"/file/errors/$testReference2")
          val result = TestFileController.getFileErrors(testReference2).apply(request)

          header("Content-Disposition", result) shouldBe Some(s"""attachment; filename="file-errors-$testReference2.csv"""")
        }

        "handle null values gracefully" in {
          val errors = Seq(
            FileRecordValidationError(new ObjectId(), Reference(testReference), null, "record1", "Error", Instant.now())
          )

          when(mockFileRecordValidationErrorRepository.findByReference(Reference(testReference)))
            .thenReturn(Future.successful(errors))

          val request = FakeRequest("GET", s"/file/errors/$testReference")
          val result = TestFileController.getFileErrors(testReference).apply(request)

          status(result) shouldBe OK
          val csvContent = contentAsString(result)
          csvContent should not be empty
        }
      }
    }
  }
}