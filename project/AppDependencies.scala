import sbt.*

object AppDependencies {

  private val bootstrapVersion = "10.7.0"
  private val hmrcMongoVersion = "2.12.0"
  private val PekkoVersion = "1.4.0"

  val compile = Seq(
    "uk.gov.hmrc"             %% "bootstrap-backend-play-30"            % bootstrapVersion,
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-work-item-repo-play-30"    % hmrcMongoVersion,
    "uk.gov.hmrc.objectstore" %% "object-store-client-play-30"          % "2.5.0",
    "uk.gov.hmrc"             %% "internal-auth-client-play-30"         % "4.3.0",

    // Explicit pekko dependencies to ensure version alignment
    "io.github.samueleresca"        %% "pekko-quartz-scheduler"           % "1.3.0-pekko-1.1.x",
    "org.apache.pekko"              %% "pekko-actor"                      % PekkoVersion,
    "org.apache.pekko"              %% "pekko-actor-typed"                % PekkoVersion,
    "org.apache.pekko"              %% "pekko-stream"                     % PekkoVersion,
    "org.apache.pekko"              %% "pekko-serialization-jackson"      % PekkoVersion,
    "org.apache.pekko"              %% "pekko-slf4j"                      % PekkoVersion,
    "org.apache.pekko"              %% "pekko-protobuf-v3"                % PekkoVersion
  )

  val test = Seq(
    "uk.gov.hmrc"             %% "bootstrap-test-play-30"              % bootstrapVersion            % Test,
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-test-play-30"             % hmrcMongoVersion            % Test,
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-work-item-repo-play-30"   % hmrcMongoVersion            % Test,
    "org.apache.pekko"        %% "pekko-testkit"                       % PekkoVersion                % Test
  )

  val it = Seq.empty
}
