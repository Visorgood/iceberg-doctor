ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "io.github.visorgood"

val icebergVersion = "1.11.0"
val hadoopVersion  = "3.5.0"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "iceberg-doctor",
    libraryDependencies ++= Seq(
      "org.apache.iceberg" %  "iceberg-core"          % icebergVersion,
      // iceberg-core does not depend on Hadoop; HadoopCatalog needs it at compile time.
      // The shaded client pair is two jars — hadoop-common declares 67 dependencies.
      "org.apache.hadoop"  %  "hadoop-client-api"     % hadoopVersion,
      "org.apache.hadoop"  %  "hadoop-client-runtime" % hadoopVersion % Runtime,
      // Silences SLF4J's "no providers were found" banner on every run.
      // Swap for a real backend once the tool has something worth logging.
      "org.slf4j"          %  "slf4j-nop"             % "2.0.17" % Runtime,
      "org.scalameta"      %% "munit"                 % "1.3.6"  % Test
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all"
    )
  )
