import sbt.*

object AppDependencies {

  private val bootstrapVersion = "10.7.0"
  private val hmrcMongoVersion = "2.12.0"

  val compile = Seq(
    "io.github.samueleresca" %% "pekko-quartz-scheduler"              % "1.2.2-pekko-1.0.x",
    "uk.gov.hmrc"             %% "bootstrap-backend-play-30"          % bootstrapVersion,
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-work-item-repo-play-30"  % hmrcMongoVersion,
    "uk.gov.hmrc.objectstore" %% "object-store-client-play-30"        % "2.5.0",
    "uk.gov.hmrc"             %% "internal-auth-client-play-30"       % "4.3.0"
  )

  val test = Seq(
    "uk.gov.hmrc"             %% "bootstrap-test-play-30"     % bootstrapVersion            % Test,
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-test-play-30"    % hmrcMongoVersion            % Test,
  )

  val it = Seq.empty
}
