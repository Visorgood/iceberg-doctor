ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "io.github.visorgood"

lazy val root = (project in file("."))
  .settings(
    name := "iceberg-doctor",
    libraryDependencies ++= Seq()
  )
