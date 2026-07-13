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

import helper.IntegrationSpec
import org.bson.types.ObjectId
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.http.Status.{NO_CONTENT, OK}
import play.api.test.Helpers.{GET, await, contentAsString, header, route, status, writeableOf_AnyContentAsEmpty}
import play.api.test.{DefaultAwaitTimeout, FakeRequest}
import uk.gov.hmrc.eacdfileprocessor.helper.TestData
import uk.gov.hmrc.eacdfileprocessor.models.{FileRecordValidationError, Reference}
import uk.gov.hmrc.eacdfileprocessor.repository.FileRecordValidationErrorRepository

import java.time.Instant
import scala.concurrent.Future

class FileControllerISpec extends TestData with DefaultAwaitTimeout with IntegrationSpec:

  lazy val fileRecordValidationErrorRepository: FileRecordValidationErrorRepository =
    app.injector.instanceOf[FileRecordValidationErrorRepository]

  val reference = "08aad019-7f66-4456-8d52-93f12109876f"

  override def beforeEach(): Unit = {
    await(fileRepository.dropCollection())
    await(fileRepository.ensureIndexes())
    await(fileRecordValidationErrorRepository.collection.drop().headOption())
    await(fileRecordValidationErrorRepository.ensureIndexes())
  }

  "GET /file-errors/:reference" should {

    "authorization" should {
      "use correct permissions for EMAC support" in {
        val controller = app.injector.instanceOf[uk.gov.hmrc.eacdfileprocessor.support.controllers.FileController]
        controller.emacSupportPermission.resource.resourceType.value shouldBe "eacd-file-processor"
        controller.emacSupportPermission.resource.resourceLocation.value shouldBe "file"
        controller.emacSupportPermission.action.value shouldBe "ADMIN"
      }

      "use correct permissions for helpdesk" in {
        val controller = app.injector.instanceOf[uk.gov.hmrc.eacdfileprocessor.support.controllers.FileController]
        controller.helpdeskPermission.resource.resourceType.value shouldBe "services-enrolments-helpdesk-frontend"
        controller.helpdeskPermission.resource.resourceLocation.value shouldBe "file"
        controller.helpdeskPermission.action.value shouldBe "ADMIN"
      }
    }

    "return 204 when no validation errors exist for the reference" in {
      val request = FakeRequest(GET, routes.FileController.getFileErrors(reference).url)
        .withHeaders("Authorization" -> "Bearer test-token")

      val result = route(app, request).get
      status(result) shouldBe NO_CONTENT
    }

    "return 200 with CSV content when validation errors exist" in {
      val request = FakeRequest(GET, routes.FileController.getFileErrors(reference).url)
        .withHeaders("Authorization" -> "Bearer test-token")

      val error = FileRecordValidationError(
        new ObjectId(),
        Reference(reference),
        "file1.csv",
        "record1",
        "Invalid format",
        Instant.parse("2024-01-01T12:00:00Z")
      )

      for {
        _ <- fileRecordValidationErrorRepository.create(error)
        result <- route(app, request).get
      } yield {
        val resultF = Future(result)
        status(resultF) shouldBe OK
        header("Content-Type", resultF) shouldBe Some("text/csv; charset=utf-8")
        header("Content-Disposition", resultF) shouldBe Some(s"""attachment; filename="file-errors-$reference.csv"""")
        contentAsString(resultF) shouldBe
          s"""reference,fileName,recordDetail,errorMessage,creationDateTime
             |$reference,file1.csv,record1,Invalid format,2024-01-01T12:00:00Z""".stripMargin
      }
    }

    "return CSV with multiple validation errors" in {
      val request = FakeRequest(GET, routes.FileController.getFileErrors(reference).url)
        .withHeaders("Authorization" -> "Bearer test-token")

      val errors = Seq(
        FileRecordValidationError(
          new ObjectId(),
          Reference(reference),
          "file1.csv",
          "record1",
          "Error 1",
          Instant.parse("2024-01-01T12:00:00Z")
        ),
        FileRecordValidationError(
          new ObjectId(),
          Reference(reference),
          "file1.csv",
          "record2",
          "Error 2",
          Instant.parse("2024-01-01T12:01:00Z")
        )
      )

      for {
        _ <- Future.sequence(errors.map(fileRecordValidationErrorRepository.create))
        result <- route(app, request).get
      } yield {
        val resultF = Future(result)
        status(resultF) shouldBe OK

        val csv = contentAsString(resultF)
        assert(csv.linesIterator.toSeq.length == 3)
        assert(csv.contains("record1"))
        assert(csv.contains("record2"))
      }
    }

    "escape commas, quotes and newlines in CSV output" in {
      val request = FakeRequest(GET, routes.FileController.getFileErrors(reference).url)
        .withHeaders("Authorization" -> "Bearer test-token")

      val error = FileRecordValidationError(
        new ObjectId(),
        Reference(reference),
        "file,1\".csv",
        "record1,with,commas\nand newlines",
        """Error "quoted" message""",
        Instant.parse("2024-01-01T12:00:00Z")
      )

      for {
        _ <- fileRecordValidationErrorRepository.create(error)
        result <- route(app, request).get
      } yield {
        val resultF = Future(result)
        status(resultF) shouldBe OK

        val csv = contentAsString(resultF)
        assert(csv.contains("\"file,1\"\".csv\""))
        assert(csv.contains("\"record1,with,commas\nand newlines\""))
        assert(csv.contains("\"Error \"\"quoted\"\" message\""))
      }
    }
  }