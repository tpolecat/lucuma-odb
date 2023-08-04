val catsParseVersion           = "0.3.10"
val catsTimeVersion            = "0.5.1"
val circeVersion               = "0.14.5"
val cirisVersion               = "3.1.0"
val clueVersion                = "0.31.1"
val declineVersion             = "2.4.1"
val disciplineMunitVersion     = "1.0.9"
val flywayVersion              = "9.20.0"
val fs2AwsVersion              = "6.0.2"
val fs2Version                 = "3.8.0"
val grackleVersion             = "0.13.0"
val http4sBlazeVersion         = "0.23.14"
val http4sEmberVersion         = "0.23.23"
val http4sJdkHttpClientVersion = "0.9.0"
val jwtVersion                 = "5.0.0"
val logbackVersion             = "1.4.8"
val log4catsVersion            = "2.6.0"
val lucumaItcVersion           = "0.17.1"
val lucumaCoreVersion          = "0.83.0"
val lucumaGraphQLRoutesVersion = "0.6.6"
val lucumaSsoVersion           = "0.6.2"
val munitVersion               = "0.7.29"
val munitCatsEffectVersion     = "1.0.7"
val munitDisciplineVersion     = "1.0.9"
val natchezHttp4sVersion       = "0.5.0"
val natchezVersion             = "0.3.1"
val paigesVersion              = "0.4.3"
val postgresVersion            = "42.6.0"
val skunkVersion               = "0.6.0"
val testcontainersScalaVersion = "0.40.14" // N.B. 0.40.15 causes java.lang.NoClassDefFoundError: munit/Test

ThisBuild / tlBaseVersion      := "0.5"
ThisBuild / scalaVersion       := "3.3.0"
ThisBuild / crossScalaVersions := Seq("3.3.0")

ThisBuild / Test / fork := false
ThisBuild / Test / parallelExecution := false


ThisBuild / githubWorkflowBuild +=
  WorkflowStep.Use(
    UseRef.Public("gemini-hlsw", "migration-validator-action", "main"),
    name = Some("Validate Migrations"),
    params = Map("path" -> "modules/service/src/main/resources/db/migration/"),
    cond = Some("github.event_name == 'pull_request'")
  )

lazy val schema =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Pure)
    .in(file("modules/schema"))
    .settings(
      name := "lucuma-odb-schema",
      libraryDependencies ++= Seq(
        "io.circe"       %%% "circe-parser"                    % circeVersion,
        "io.circe"       %%% "circe-literal"                   % circeVersion,
        "io.circe"       %%% "circe-refined"                   % circeVersion,
        "io.circe"       %%% "circe-testing"                   % circeVersion               % Test,
        "edu.gemini"     %%% "lucuma-core"                     % lucumaCoreVersion,
        "edu.gemini"     %%% "lucuma-core-testkit"             % lucumaCoreVersion          % Test,
        "org.scalameta"  %%% "munit"                           % munitVersion               % Test,
        "org.scalameta"  %%% "munit-scalacheck"                % munitVersion               % Test,
        "org.typelevel"  %%% "discipline-munit"                % munitDisciplineVersion     % Test,
      )
    )

lazy val sequence = project
  .in(file("modules/sequence"))
  .dependsOn(schema.jvm)
  .enablePlugins(NoPublishPlugin)
  .settings(
    name := "lucuma-odb-sequence",
    libraryDependencies ++= Seq(
      "edu.gemini"     %% "lucuma-itc-client"               % lucumaItcVersion,
      "edu.gemini"     %% "lucuma-itc-testkit"              % lucumaItcVersion          % Test,
      "org.scalameta"  %% "munit"                           % munitVersion              % Test,
      "org.scalameta"  %% "munit-scalacheck"                % munitVersion              % Test,
      "org.typelevel"  %% "discipline-munit"                % munitDisciplineVersion    % Test,
    )
  )

lazy val smartgcal = project
  .in(file("modules/smartgcal"))
  .enablePlugins(NoPublishPlugin)
  .settings(
    name := "lucuma-odb-smartgcal",
    libraryDependencies ++= Seq(
      "org.typelevel"  %% "cats-parse"                      % catsParseVersion,
      "co.fs2"         %% "fs2-core"                        % fs2Version,
      "co.fs2"         %% "fs2-io"                          % fs2Version,
      "edu.gemini"     %% "lucuma-core"                     % lucumaCoreVersion,
      "edu.gemini"     %% "lucuma-core-testkit"             % lucumaCoreVersion          % Test,
      "org.scalameta"  %% "munit"                           % munitVersion               % Test,
      "org.scalameta"  %% "munit-scalacheck"                % munitVersion               % Test,
      "org.typelevel"  %% "discipline-munit"                % munitDisciplineVersion     % Test,
    )
  )

lazy val service = project
  .in(file("modules/service"))
  .dependsOn(sequence, smartgcal)
  .enablePlugins(NoPublishPlugin, JavaAppPackaging)
  .settings(
    name := "lucuma-odb-service",
    projectDependencyArtifacts := (Compile / dependencyClasspathAsJars).value,
    libraryDependencies ++= Seq(
      "ch.qos.logback" %  "logback-classic"                    % logbackVersion,
      "com.monovore"   %% "decline-effect"                     % declineVersion,
      "com.monovore"   %% "decline"                            % declineVersion,
      "io.laserdisc"   %% "fs2-aws-s3"                         % fs2AwsVersion,
      "edu.gemini"     %% "gsp-graphql-skunk"                  % grackleVersion,
      "edu.gemini"     %% "lucuma-graphql-routes-grackle"      % lucumaGraphQLRoutesVersion,
      "edu.gemini"     %% "lucuma-sso-backend-client"          % lucumaSsoVersion,
      "is.cir"         %% "ciris"                              % cirisVersion,
      "is.cir"         %% "ciris-refined"                      % cirisVersion,
      "org.flywaydb"   %  "flyway-core"                        % flywayVersion,
      "org.http4s"     %% "http4s-jdk-http-client"             % http4sJdkHttpClientVersion,
      "org.http4s"     %% "http4s-blaze-server"                % http4sBlazeVersion,
      "org.http4s"     %% "http4s-ember-client"                % http4sEmberVersion,
      "org.postgresql" %  "postgresql"                         % postgresVersion,
      "org.tpolecat"   %% "natchez-honeycomb"                  % natchezVersion,
      "org.tpolecat"   %% "natchez-http4s"                     % natchezHttp4sVersion,
      "org.tpolecat"   %% "natchez-log"                        % natchezVersion,
      "org.tpolecat"   %% "skunk-core"                         % skunkVersion,
      "org.tpolecat"   %% "skunk-circe"                        % skunkVersion,
      "com.dimafeng"   %% "testcontainers-scala-munit"         % testcontainersScalaVersion % Test,
      "com.dimafeng"   %% "testcontainers-scala-localstack-v2" % testcontainersScalaVersion % Test,
      "com.dimafeng"   %% "testcontainers-scala-postgresql"    % testcontainersScalaVersion % Test,
      // testcontainers-scala-localstack-v2 requires both v1 and v2 of the aws sdk
      "io.circe"       %% "circe-testing"                      % circeVersion               % Test,
      "com.amazonaws"  %  "aws-java-sdk-core"                  % "1.12.522"                 % Test,
      "edu.gemini"     %% "clue-http4s"                        % clueVersion                % Test,
      "org.scalameta"  %% "munit"                              % munitVersion               % Test,
      "org.scalameta"  %% "munit-scalacheck"                   % munitVersion               % Test,
      "org.typelevel"  %% "discipline-munit"                   % munitDisciplineVersion     % Test,
      "edu.gemini"     %% "lucuma-core-testkit"                % lucumaCoreVersion          % Test,
      "org.typelevel"  %% "cats-time"                          % catsTimeVersion,
      "org.typelevel"  %% "log4cats-slf4j"                     % log4catsVersion,
      "org.typelevel"  %% "munit-cats-effect-3"                % munitCatsEffectVersion     % Test,
      "org.typelevel"  %% "paiges-core"                        % paigesVersion,
      "com.github.vertical-blank" % "sql-formatter" % "2.0.3",
    ),
    reStart / envVars += "PORT" -> "8082",
    reStartArgs       += "serve"
  )

