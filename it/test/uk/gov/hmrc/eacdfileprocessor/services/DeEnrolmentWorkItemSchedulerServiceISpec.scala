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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{reset, when}
import org.mongodb.scala.model.Filters
import org.mongodb.scala.{Document, SingleObservableFuture}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.connectors.EspConnector
import uk.gov.hmrc.eacdfileprocessor.connectors.Sec0Connector
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, UnitSpec}
import uk.gov.hmrc.eacdfileprocessor.models.DeEnrolmentWorkItem
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemMongoRepository, FileRecordValidationErrorRepository}
import uk.gov.hmrc.eacdfileprocessor.utils.DeEnrolmentWorkItemValidator
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.time.{Clock, Instant}
import scala.concurrent.Future

class DeEnrolmentWorkItemSchedulerServiceISpec extends IntegrationSpec with TestData with UnitSpec with Eventually:
  val materializer: Materializer = mock[Materializer]
  val appConfiguration: uk.gov.hmrc.eacdfileprocessor.config.AppConfig = appConfig
  val executionContext: scala.concurrent.ExecutionContext = ec
  val fileRepo: uk.gov.hmrc.eacdfileprocessor.repository.FileRepository = fileRepository
  val fileRecordValidationErrorRepository: FileRecordValidationErrorRepository = app.injector.instanceOf[FileRecordValidationErrorRepository]
  val mockLockService = new LockService(lockingRepo)

  private val deEnrolmentWorkItemRepository = new DeEnrolmentWorkItemMongoRepository(mongoRepository, appConfiguration)
  private val mockEspConnector = mock[EspConnector]

  private val mockAgentServiceCache = new AgentServiceCache(
    sec0Connector = mock[Sec0Connector],
    appConfig = appConfiguration,
    clock = Clock.systemUTC()
  ) {
    override def getAgentServices()(using HeaderCarrier): Future[Set[String]] =
      Future.successful(Set("IR-SA", "VAT"))
  }

  private val mockValidator = mock[DeEnrolmentWorkItemValidator]

  private val deEnrolmentWorkItemSchedulerService = new DeEnrolmentWorkItemSchedulerService(
    appConfiguration,
    deEnrolmentWorkItemRepository,
    fileRecordValidationErrorRepository,
    fileRepo,
    mockEspConnector,
    mockLockService,
    mockAgentServiceCache,
    mockValidator
  )

   override def beforeEach(): Unit = {
     reset(mockEspConnector, mockValidator)
     await(fileRepository.collection.drop().headOption())
     await(fileRepository.ensureIndexes())
     await(deEnrolmentWorkItemRepository.collection.deleteMany(Filters.exists("_id")).toFuture())
     await(lockingRepo.collection.deleteMany(filter = Document()).toFuture())
   }

  "DeEnrolment work item scheduler invoking" must {
    "return a correct total successful record" when {
      "The action is principal and ES1 returns a 204 with a single record" in {
        val payload = DeEnrolmentWorkItem(
          reference = scannedUploadedDetails.reference.value,
          recordDetail = "IR-SA~UTR~1234567890,principal",
          creationDateTime = Instant.now()
        )
         when(mockEspConnector.callES1(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(204, body = "")))

         when(mockValidator.validate(payload.recordDetail, Set("IR-SA", "VAT")))
           .thenReturn(Right(("IR-SA~UTR~1234567890", "principal")))

        await(deEnrolmentWorkItemRepository.saveRecordDetails(Seq(payload), scannedUploadedDetails.reference.value))
        await(fileRepository.createFileRecord(scannedUploadedDetails))

        await(deEnrolmentWorkItemSchedulerService.invoke)
        eventually {
         val successCount = await(fileRepository.findByReference(scannedUploadedDetails.reference)).get.totalSuccessCount
          successCount shouldBe Some(1)
        }
      }

      "The action is delegated and ES1 returns a 204 with a single record" in {
        val payload = DeEnrolmentWorkItem(
          reference = scannedUploadedDetails.reference.value,
          recordDetail = "IR-SA~UTR~1234567890,delegated",
          creationDateTime = Instant.now()
        )

         when(mockEspConnector.callES1(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(204, body = "")))


         when(mockValidator.validate(payload.recordDetail, Set("IR-SA", "VAT")))
           .thenReturn(Right(("IR-SA~UTR~1234567890", "delegated")))

        await(deEnrolmentWorkItemRepository.saveRecordDetails(Seq(payload), scannedUploadedDetails.reference.value))
        await(fileRepository.createFileRecord(scannedUploadedDetails))

        await(deEnrolmentWorkItemSchedulerService.invoke)
        eventually {
          val successCount = await(fileRepository.findByReference(scannedUploadedDetails.reference)).get.totalSuccessCount
          successCount shouldBe Some(1)
        }
      }

      "The action is delegated and ES1 returns a 200 and ES9 returns 204 with a single record" in {
        val payload = DeEnrolmentWorkItem(
          reference = scannedUploadedDetails.reference.value,
          recordDetail = "IR-SA~UTR~1234567890,delegated",
          creationDateTime = Instant.now()
        )

         val responseBody =
           """{
             |    "delegatedGroupIds": [
             |       "c0506dd9-1feb-400a-bf70-6351e1ff7510"
             |    ]
             |}""".stripMargin

         when(mockEspConnector.callES1(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(200, responseBody)))
         when(mockEspConnector.callES9(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(204, body = "")))

        when(mockValidator.validate(payload.recordDetail, Set("IR-SA", "VAT")))
          .thenReturn(Right(("IR-SA~UTR~1234567890", "delegated")))

        await(deEnrolmentWorkItemRepository.saveRecordDetails(Seq(payload), scannedUploadedDetails.reference.value))
        await(fileRepository.createFileRecord(scannedUploadedDetails))

        await(deEnrolmentWorkItemSchedulerService.invoke)
        eventually {
          val successCount = await(fileRepository.findByReference(scannedUploadedDetails.reference)).get.totalSuccessCount
          successCount shouldBe Some(1)
        }
      }

      "The action is principal and ES1 returns a 200 and ES9 returns 204 with a single record" in {
        val payload = DeEnrolmentWorkItem(
          reference = scannedUploadedDetails.reference.value,
          recordDetail = "IR-SA~UTR~1234567890,principal",
          creationDateTime = Instant.now()
        )

         val responseBody =
           """{
             |    "principalGroupIds": [
             |       "c0506dd9-1feb-400a-bf70-6351e1ff7510"
             |    ]
             |}""".stripMargin

         when(mockEspConnector.callES1(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(200, responseBody)))
         when(mockEspConnector.callES9(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(204, body = "")))

        when(mockValidator.validate(payload.recordDetail, Set("IR-SA", "VAT")))
          .thenReturn(Right(("IR-SA~UTR~1234567890", "principal")))

        await(deEnrolmentWorkItemRepository.saveRecordDetails(Seq(payload), scannedUploadedDetails.reference.value))
        await(fileRepository.createFileRecord(scannedUploadedDetails))

        await(deEnrolmentWorkItemSchedulerService.invoke)
        eventually {
          val successCount = await(fileRepository.findByReference(scannedUploadedDetails.reference)).get.totalSuccessCount
          successCount shouldBe Some(1)
        }
      }
      
      "The action is both principal and delegated and ES1 returns a 204 with a single record" in {
        val payload = DeEnrolmentWorkItem(
          reference = scannedUploadedDetails.reference.value,
          recordDetail = "IR-SA~UTR~1234567890,both",
          creationDateTime = Instant.now()
        )

         when(mockEspConnector.callES1(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(204, body = "")))

        when(mockValidator.validate(payload.recordDetail, Set("IR-SA", "VAT")))
          .thenReturn(Right(("IR-SA~UTR~1234567890", "both")))

        await(deEnrolmentWorkItemRepository.saveRecordDetails(Seq(payload), scannedUploadedDetails.reference.value))
        await(fileRepository.createFileRecord(scannedUploadedDetails))

        await(deEnrolmentWorkItemSchedulerService.invoke)
        eventually {
          val successCount = await(fileRepository.findByReference(scannedUploadedDetails.reference)).get.totalSuccessCount
          successCount shouldBe Some(1)
        }
      }
      
      "The action is both principal and delegated and ES1 returns a 200 and ES9 returns 204 with a single record" in {
        val payload = DeEnrolmentWorkItem(
          reference = scannedUploadedDetails.reference.value,
          recordDetail = "IR-SA~UTR~1234567890,both",
          creationDateTime = Instant.now()
        )

         val responseBody =
           """{
             |    "principalGroupIds": [
             |       "c0506dd9-1feb-400a-bf70-6351e1ff7510"
             |    ],
             |    "delegatedGroupIds": [
             |       "c0506dd9-1feb-400a-bf70-6351e1ff7510"
             |    ]
             |}""".stripMargin

         when(mockEspConnector.callES1(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(200, responseBody)))
         when(mockEspConnector.callES9(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(204, body = "")))

        when(mockValidator.validate(payload.recordDetail, Set("IR-SA", "VAT")))
          .thenReturn(Right(("IR-SA~UTR~1234567890", "both")))

        await(deEnrolmentWorkItemRepository.saveRecordDetails(Seq(payload), scannedUploadedDetails.reference.value))
        await(fileRepository.createFileRecord(scannedUploadedDetails))

        await(deEnrolmentWorkItemSchedulerService.invoke)
        eventually {
          val successCount = await(fileRepository.findByReference(scannedUploadedDetails.reference)).get.totalSuccessCount
          successCount shouldBe Some(1)
        }
      }
    }

    "return total error record" when {
      val responseBodyInternalServerError =
        """
          |{
          |    "code": "INTERNAL_SERVER_ERROR",
          |    "message": "An unexpected error occurred"
          |}
          |""".stripMargin

      "The action is principal and ES1 returns a 500 with a single record" in {
        val payload = DeEnrolmentWorkItem(
          reference = scannedUploadedDetails.reference.value,
          recordDetail = "IR-SA~UTR~1234567890,principal",
          creationDateTime = Instant.now()
        )

        when(mockEspConnector.callES1(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(500, responseBodyInternalServerError)))

        when(mockValidator.validate(payload.recordDetail, Set("IR-SA", "VAT")))
          .thenReturn(Right(("IR-SA~UTR~1234567890", "principal")))

        await(deEnrolmentWorkItemRepository.saveRecordDetails(Seq(payload), scannedUploadedDetails.reference.value))
        await(fileRepository.createFileRecord(scannedUploadedDetails))

        await(deEnrolmentWorkItemSchedulerService.invoke)
        eventually {
          val errorCount = await(fileRepository.findByReference(scannedUploadedDetails.reference)).get.totalFailureCount
          errorCount shouldBe Some(1)
        }
      }

      "The action is delegated and ES1 returns a 500 with a single record" in {
        val payload = DeEnrolmentWorkItem(
          reference = scannedUploadedDetails.reference.value,
          recordDetail = "IR-SA~UTR~1234567890,delegated",
          creationDateTime = Instant.now()
        )

        when(mockEspConnector.callES1(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(500, responseBodyInternalServerError)))

        when(mockValidator.validate(payload.recordDetail, Set("IR-SA", "VAT")))
          .thenReturn(Right(("IR-SA~UTR~1234567890", "delegated")))

        await(deEnrolmentWorkItemRepository.saveRecordDetails(Seq(payload), scannedUploadedDetails.reference.value))
        await(fileRepository.createFileRecord(scannedUploadedDetails))

        await(deEnrolmentWorkItemSchedulerService.invoke)
        eventually {
          val errorCount = await(fileRepository.findByReference(scannedUploadedDetails.reference)).get.totalFailureCount
          errorCount shouldBe Some(1)
        }
      }

      "The action is delegated and ES1 returns a 200 and ES9 returns 500 with a single record" in {
        val payload = DeEnrolmentWorkItem(
          reference = scannedUploadedDetails.reference.value,
          recordDetail = "IR-SA~UTR~1234567890,delegated",
          creationDateTime = Instant.now()
        )

        val responseBody =
          """{
            |    "delegatedGroupIds": [
            |       "c0506dd9-1feb-400a-bf70-6351e1ff7510"
            |    ]
            |}""".stripMargin

        when(mockEspConnector.callES1(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(200, responseBody)))
        when(mockEspConnector.callES9(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(500, responseBodyInternalServerError)))

        when(mockValidator.validate(payload.recordDetail, Set("IR-SA", "VAT")))
          .thenReturn(Right(("IR-SA~UTR~1234567890", "delegated")))

        await(deEnrolmentWorkItemRepository.saveRecordDetails(Seq(payload), scannedUploadedDetails.reference.value))
        await(fileRepository.createFileRecord(scannedUploadedDetails))

        await(deEnrolmentWorkItemSchedulerService.invoke)
        eventually {
          val errorCount = await(fileRepository.findByReference(scannedUploadedDetails.reference)).get.totalFailureCount
          errorCount shouldBe Some(1)
        }
      }

      "The action is principal and ES1 returns a 200 and ES9 returns 500 with a single record" in {
        val payload = DeEnrolmentWorkItem(
          reference = scannedUploadedDetails.reference.value,
          recordDetail = "IR-SA~UTR~1234567890,principal",
          creationDateTime = Instant.now()
        )

        val responseBody =
          """{
            |    "principalGroupIds": [
            |       "c0506dd9-1feb-400a-bf70-6351e1ff7510"
            |    ]
            |}""".stripMargin

        when(mockEspConnector.callES1(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(200, responseBody)))
        when(mockEspConnector.callES9(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(500, responseBodyInternalServerError)))

        when(mockValidator.validate(payload.recordDetail, Set("IR-SA", "VAT")))
          .thenReturn(Right(("IR-SA~UTR~1234567890", "principal")))

        await(deEnrolmentWorkItemRepository.saveRecordDetails(Seq(payload), scannedUploadedDetails.reference.value))
        await(fileRepository.createFileRecord(scannedUploadedDetails))

        await(deEnrolmentWorkItemSchedulerService.invoke)
        eventually {
          val errorCount = await(fileRepository.findByReference(scannedUploadedDetails.reference)).get.totalFailureCount
          errorCount shouldBe Some(1)
        }
      }

      "The action is both principal and delegated and ES1 returns a 500" in {
        val payload = DeEnrolmentWorkItem(
          reference = scannedUploadedDetails.reference.value,
          recordDetail = "IR-SA~UTR~1234567890,both",
          creationDateTime = Instant.now()
        )

        when(mockEspConnector.callES1(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(500, responseBodyInternalServerError)))

        when(mockValidator.validate(payload.recordDetail, Set("IR-SA", "VAT")))
          .thenReturn(Right(("IR-SA~UTR~1234567890", "both")))

        await(deEnrolmentWorkItemRepository.saveRecordDetails(Seq(payload), scannedUploadedDetails.reference.value))
        await(fileRepository.createFileRecord(scannedUploadedDetails))

        await(deEnrolmentWorkItemSchedulerService.invoke)
        eventually {
          val errorCount = await(fileRepository.findByReference(scannedUploadedDetails.reference)).get.totalFailureCount
          errorCount shouldBe Some(1)
        }
      }
      
      "The action is both principal and delegated and ES1 returns a 200 and ES9 returns 500" in {
        val payload = DeEnrolmentWorkItem(
          reference = scannedUploadedDetails.reference.value,
          recordDetail = "IR-SA~UTR~1234567890,both",
          creationDateTime = Instant.now()
        )

        val responseBody =
          """{
            |    "principalGroupIds": [
            |       "c0506dd9-1feb-400a-bf70-6351e1ff7510"
            |    ],
            |    "delegatedGroupIds": [
            |       "c0506dd9-1feb-400a-bf70-6351e1ff7510"
            |    ]
            |}""".stripMargin

        when(mockEspConnector.callES1(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(200, responseBody)))
        when(mockEspConnector.callES9(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(500, responseBodyInternalServerError)))

        when(mockValidator.validate(payload.recordDetail, Set("IR-SA", "VAT")))
          .thenReturn(Right(("IR-SA~UTR~1234567890", "both")))

        await(deEnrolmentWorkItemRepository.saveRecordDetails(Seq(payload), scannedUploadedDetails.reference.value))
        await(fileRepository.createFileRecord(scannedUploadedDetails))

        await(deEnrolmentWorkItemSchedulerService.invoke)
        eventually {
          val errorCount = await(fileRepository.findByReference(scannedUploadedDetails.reference)).get.totalFailureCount
          errorCount shouldBe Some(1)
        }
      }
      
      "The action is both principal and delegated and ES1 returns a 200 and a ES9 call returns a 500 and another ES9 call returns a 204" in {
        val payload = DeEnrolmentWorkItem(
          reference = scannedUploadedDetails.reference.value,
          recordDetail = "IR-SA~UTR~1234567890,both",
          creationDateTime = Instant.now()
        )

        val responseBody =
          """{
            |    "principalGroupIds": [
            |       "c0506dd9-1feb-400a-bf70-6351e1ff7510"
            |    ],
            |    "delegatedGroupIds": [
            |       "c0506dd9-1feb-400a-bf70-6351e1ff7510"
            |    ]
            |}""".stripMargin


        when(mockEspConnector.callES1(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(200, responseBody)))
        when(mockEspConnector.callES9(any[String], any[String])(using any[HeaderCarrier]))
          .thenReturn(Future.successful(HttpResponse(204, "")))
          .thenReturn(Future.successful(HttpResponse(500, responseBodyInternalServerError)))

        when(mockValidator.validate(payload.recordDetail, Set("IR-SA", "VAT")))
          .thenReturn(Right(("IR-SA~UTR~1234567890", "both")))

        await(deEnrolmentWorkItemRepository.saveRecordDetails(Seq(payload), scannedUploadedDetails.reference.value))
        await(fileRepository.createFileRecord(scannedUploadedDetails))

        await(deEnrolmentWorkItemSchedulerService.invoke)
        eventually {
          val errorCount = await(fileRepository.findByReference(scannedUploadedDetails.reference)).get.totalFailureCount
          errorCount shouldBe Some(1)
        }
      }
      
    }
  }

