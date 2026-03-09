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

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.test.Helpers.*
import play.api.test.{DefaultAwaitTimeout, FakeRequest, FutureAwaits}
import org.mongodb.scala._
import org.mongodb.scala.model.Filters
import scala.concurrent.Await
import scala.concurrent.duration._

class InitiateFileStorageControllerISpec
  extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with FutureAwaits
    with DefaultAwaitTimeout {

  def buildApp: Application = new GuiceApplicationBuilder()
    .configure(
      "mongodb.uri" -> "mongodb://localhost:27017/eacd-file-processor-it",
      "play.http.router" -> "app.Routes"
    )
    .build()

  private def cleanTestReference(ref: String): Unit = {
    val mongoClient = MongoClient("mongodb://localhost:27017")
    val db = mongoClient.getDatabase("eacd-file-processor-it")
    val collection = db.getCollection("file")
    Await.result(collection.deleteMany(Filters.equal("reference", ref)).toFuture(), 10.seconds)
    mongoClient.close()
  }

  "POST /initiate (integration)" should {
    "return 201 Created for valid request" in {
      cleanTestReference("ref-integration-1")
      val app = buildApp
      running(app) {
        val json = Json.obj(
          "reference" -> "ref-integration-1",
          "requestorPID" -> "pid-integration-1",
          "requestorEmail" -> "integration@example.com",
          "requestorName" -> "Integration User"
        )
        val request = FakeRequest(POST, "/initiate")
          .withJsonBody(json)
          .withHeaders("Authorization" -> "Bearer test-token")
        val result = route(app, request).get
        println("RESPONSE BODY: " + contentAsString(result))
        status(result) shouldBe CREATED
      }
    }

    "return 400 for missing fields" in {
      val app = buildApp
      running(app) {
        val request = FakeRequest(POST, "/initiate").withJsonBody(Json.obj("reference" -> "ref1"))
        val result = route(app, request).get
        status(result) shouldBe BAD_REQUEST
        (contentAsJson(result) \ "errorCode").as[String] shouldBe "MANDATORY_FIELDS_MISSING"
      }
    }

    "return 400 for invalid email" in {
      val app = buildApp
      running(app) {
        val json = Json.obj(
          "reference" -> "ref1",
          "requestorPID" -> "pid1",
          "requestorEmail" -> "invalid-email",
          "requestorName" -> "Test User"
        )
        val request = FakeRequest(POST, "/initiate").withJsonBody(json)
        val result = route(app, request).get
        status(result) shouldBe BAD_REQUEST
        (contentAsJson(result) \ "errorCode").as[String] shouldBe "INVALID_REQUESTOR_EMAIL"
      }
    }

    "return 400 for duplicate reference" in {
      cleanTestReference("ref-dup-1")
      val app = buildApp
      running(app) {
        val json = Json.obj(
          "reference" -> "ref-dup-1",
          "requestorPID" -> "pid-dup-1",
          "requestorEmail" -> "dup@example.com",
          "requestorName" -> "Dup User"
        )
        val request = FakeRequest(POST, "/initiate").withJsonBody(json)
        val _ = route(app, request).get
        val result = route(app, request).get
        status(result) shouldBe BAD_REQUEST
        (contentAsJson(result) \ "errorCode").as[String] shouldBe "DUPLICATE_EXTERNAL_FILE_REF"
      }
    }

    "return 400 for non-JSON body" in {
      val app = buildApp
      running(app) {
        val request = FakeRequest(POST, "/initiate").withTextBody("not json")
        val result = route(app, request).get
        status(result) shouldBe UNSUPPORTED_MEDIA_TYPE
        (contentAsJson(result) \ "errorCode").as[String] shouldBe "MANDATORY_FIELDS_MISSING"
      }
    }

    "store all fields, fileStatus as 'initial', and creationDateTime in Mongo for valid request" in {
      cleanTestReference("ref-integration-2")
      val app = buildApp
      running(app) {
        val json = Json.obj(
          "reference" -> "ref-integration-2",
          "requestorPID" -> "pid-integration-2",
          "requestorEmail" -> "integration2@example.com",
          "requestorName" -> "Integration User 2"
        )
        val request = FakeRequest(POST, "/initiate")
          .withJsonBody(json)
          .withHeaders("Authorization" -> "Bearer test-token")
        val result = route(app, request).get
        status(result) shouldBe CREATED
        
        val mongoClient = MongoClient("mongodb://localhost:27017")
        val db = mongoClient.getDatabase("eacd-file-processor-it")
        val collection = db.getCollection("file")
        val docOpt = Await.result(collection.find(Filters.equal("reference", "ref-integration-2")).first().toFutureOption(), 10.seconds)
        mongoClient.close()
        docOpt should not be empty
        val doc = docOpt.get
        doc.getString("reference") shouldBe "ref-integration-2"
        doc.getString("requestorPID") shouldBe "pid-integration-2"
        doc.getString("requestorEmail") shouldBe "integration2@example.com"
        doc.getString("requestorName") shouldBe "Integration User 2"
        doc.getString("fileStatus") shouldBe "initial"
        doc.containsKey("creationDateTime") shouldBe true
      }
    }
  }
}
