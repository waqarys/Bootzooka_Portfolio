import sbtbuildinfo.BuildInfoKey.action
import sbtbuildinfo.BuildInfoKeys.{buildInfoKeys, buildInfoOptions, buildInfoPackage}
import sbtbuildinfo.{BuildInfoKey, BuildInfoOption}
import com.typesafe.sbt.packager.docker.ExecCmd

import sbt._
import Keys._

import scala.util.Try
import scala.sys.process.Process
import complete.DefaultParsers._

val doobieVersion = "0.13.4"
val http4sVersion = "0.21.24"
val circeVersion = "0.14.1"
val tsecVersion = "0.2.1"
val sttpVersion = "3.3.6"
val prometheusVersion = "0.11.0"
val tapirVersion = "0.17.19"

val dbDependencies = Seq(
  "org.tpolecat" %% "doobie-core" % doobieVersion,
  "org.tpolecat" %% "doobie-hikari" % doobieVersion,
  "org.tpolecat" %% "doobie-postgres" % doobieVersion,
  "org.flywaydb" % "flyway-core" % "7.10.0"
)

val httpDependencies = Seq(
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-blaze-server" % http4sVersion,
  "org.http4s" %% "http4s-blaze-client" % http4sVersion,
  "org.http4s" %% "http4s-circe" % http4sVersion,
  "org.http4s" %% "http4s-prometheus-metrics" % http4sVersion,
  "com.softwaremill.sttp.client3" %% "async-http-client-backend-monix" % sttpVersion,
  "com.softwaremill.sttp.client3" %% "slf4j-backend" % sttpVersion,
  "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % tapirVersion
)

val monitoringDependencies = Seq(
  "io.prometheus" % "simpleclient" % prometheusVersion,
  "io.prometheus" % "simpleclient_hotspot" % prometheusVersion,
  "com.softwaremill.sttp.client3" %% "prometheus-backend" % sttpVersion
)

val jsonDependencies = Seq(
  "io.circe" %% "circe-core" % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion,
  "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % tapirVersion,
  "com.softwaremill.sttp.client3" %% "circe" % sttpVersion
)

val loggingDependencies = Seq(
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.3",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "org.codehaus.janino" % "janino" % "3.1.4",
  "de.siegmar" % "logback-gelf" % "3.0.0",
  "com.softwaremill.correlator" %% "monix-logback-http4s" % "0.1.9"
)

val configDependencies = Seq(
  "com.github.pureconfig" %% "pureconfig" % "0.16.0"
)

val baseDependencies = Seq(
  "io.monix" %% "monix" % "3.4.0",
  "com.softwaremill.common" %% "tagging" % "2.3.1",
  "com.softwaremill.quicklens" %% "quicklens" % "1.7.4"
)

val apiDocsDependencies = Seq(
  "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs" % tapirVersion,
  "com.softwaremill.sttp.tapir" %% "tapir-openapi-circe-yaml" % tapirVersion,
  "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-http4s" % tapirVersion
)

val securityDependencies = Seq(
  "io.github.jmcardon" %% "tsec-password" % tsecVersion,
  "io.github.jmcardon" %% "tsec-cipher-jca" % tsecVersion
)

val emailDependencies = Seq(
  "com.sun.mail" % "javax.mail" % "1.6.2" exclude("javax.activation", "activation")
)

val scalatest = "org.scalatest" %% "scalatest" % "3.2.9" % Test
val unitTestingStack = Seq(scalatest)

val embeddedPostgres = "com.opentable.components" % "otj-pg-embedded" % "0.13.4" % Test
val dbTestingStack = Seq(embeddedPostgres)

val catsEffectStack = Seq(
  "org.typelevel" %% "cats-effect-laws" % "2.3.3" % Test
)

val commonDependencies = baseDependencies ++ unitTestingStack ++ loggingDependencies ++ configDependencies

lazy val uiProjectName = "ui"
lazy val uiDirectory = settingKey[File]("Path to the ui project directory")
lazy val updateYarn = taskKey[Unit]("Update yarn")
lazy val yarnTask = inputKey[Unit]("Run yarn with arguments")
lazy val copyWebapp = taskKey[Unit]("Copy webapp")

lazy val commonSettings = commonSmlBuildSettings ++ Seq(
  organization := "com.softwaremill.bootzooka",
  scalaVersion := "2.13.3",
  libraryDependencies ++= commonDependencies,
  uiDirectory := baseDirectory.value.getParentFile / uiProjectName,
  updateYarn := {
    streams.value.log("Updating npm/yarn dependencies")
    haltOnCmdResultError(Process("yarn install", uiDirectory.value).!)
  },
  yarnTask := {
    val taskName = spaceDelimited("<arg>").parsed.mkString(" ")
    updateYarn.value
    val localYarnCommand = "yarn " + taskName
    def runYarnTask() = Process(localYarnCommand, uiDirectory.value).!
    streams.value.log("Running yarn task: " + taskName)
    haltOnCmdResultError(runYarnTask())
  },
  copyWebapp := {
    streams.value.log.info("Copying the webapp resources")
    IO.copyDirectory(uiDirectory.value / "build", (Compile / classDirectory).value / "webapp")
  },
  copyWebapp := copyWebapp.dependsOn(yarnTask.toTask(" build")).value
)

lazy val buildInfoSettings = Seq(
  buildInfoKeys := Seq[BuildInfoKey](
    name,
    version,
    scalaVersion,
    sbtVersion,
    action("lastCommitHash") {
      import scala.sys.process._
      // if the build is done outside of a git repository, we still want it to succeed
      Try("git rev-parse HEAD".!!.trim).getOrElse("?")
    }
  ),
  buildInfoOptions += BuildInfoOption.ToJson,
  buildInfoOptions += BuildInfoOption.ToMap,
  buildInfoPackage := "com.softwaremill.bootzooka.version",
  buildInfoObject := "BuildInfo"
)

lazy val fatJarSettings = Seq(
  assembly / assemblyJarName := "bootzooka.jar",
  assembly := assembly.dependsOn(copyWebapp).value,
  assembly / assemblyMergeStrategy := {
    case PathList(ps @ _*) if ps.last endsWith "io.netty.versions.properties"       => MergeStrategy.first
    case PathList(ps @ _*) if ps.last endsWith "pom.properties"                     => MergeStrategy.first
    case PathList(ps @ _*) if ps.last endsWith "scala-collection-compat.properties" => MergeStrategy.first
    case x =>
      val oldStrategy = (assembly / assemblyMergeStrategy).value
      oldStrategy(x)
  }
)

lazy val dockerSettings = Seq(
  dockerExposedPorts := Seq(8080),
  dockerBaseImage := "adoptopenjdk:11.0.5_10-jdk-hotspot",
  Docker / packageName := "bootzooka",
  dockerUsername := Some("softwaremill"),
  dockerCommands := {
    dockerCommands.value.flatMap {
      case ep @ ExecCmd("ENTRYPOINT", _*) =>
        Seq(
          ExecCmd("ENTRYPOINT", "/opt/docker/docker-entrypoint.sh" :: ep.args.toList: _*)
        )
      case other => Seq(other)
    }
  },
  Docker / mappings ++= {
    val scriptDir = baseDirectory.value / ".." / "scripts"
    val entrypointScript = scriptDir / "docker-entrypoint.sh"
    val entrypointScriptTargetPath = "/opt/docker/docker-entrypoint.sh"
    Seq(entrypointScript -> entrypointScriptTargetPath)
  },
  dockerUpdateLatest := true,
  Docker / publishLocal := (Docker / publishLocal).dependsOn(copyWebapp).value,
  Docker / version := git.gitHeadCommit.value.map(head => now() + "-" + head.take(8)).getOrElse("latest")
)

def haltOnCmdResultError(result: Int) {
  if (result != 0) {
    throw new Exception("Build failed.")
  }
}

def now(): String = {
  import java.text.SimpleDateFormat
  import java.util.Date
  new SimpleDateFormat("yyyy-MM-dd-hhmmss").format(new Date())
}

lazy val rootProject = (project in file("."))
  .settings(commonSettings)
  .settings(
    name := "bootzooka",
    Compile / herokuFatJar := Some((backend / assembly / assemblyOutputPath).value),
    Compile / deployHeroku := ((Compile / deployHeroku) dependsOn (backend / assembly)).value
  )
  .aggregate(backend, ui)

lazy val backend: Project = (project in file("backend"))
  .settings(
    libraryDependencies ++= dbDependencies ++ httpDependencies ++ jsonDependencies ++ apiDocsDependencies ++ monitoringDependencies ++ dbTestingStack ++ securityDependencies ++ emailDependencies ++ catsEffectStack,
    Compile / mainClass := Some("com.softwaremill.bootzooka.Main")
  )
  .enablePlugins(BuildInfoPlugin)
  .settings(commonSettings)
  .settings(Revolver.settings)
  .settings(buildInfoSettings)
  .settings(fatJarSettings)
  .enablePlugins(DockerPlugin)
  .enablePlugins(JavaServerAppPackaging)
  .settings(dockerSettings)

lazy val ui = (project in file(uiProjectName))
  .settings(commonSettings)
  .settings(Test / test := (Test / test).dependsOn(yarnTask.toTask(" test:ci")).value)
  .settings(cleanFiles += baseDirectory.value / "build")

RenameProject.settings
