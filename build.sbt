import Dependencies._

ThisBuild / scalaVersion     := "3.8.4"
ThisBuild / version          := "1.0.0-dev"
ThisBuild / organization     := "com.cloud-apim"
ThisBuild / organizationName := "Cloud-APIM"

lazy val root = (project in file("."))
  .settings(
    name := "otoroshi-plugin-dynamic-js-modules",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      // the wasm4s "bundle" jar vendors a whole scala 2.13 library, so `scala.caps` shows up as both
      // a package and an object on the compile classpath. otoroshi itself silences the same warning.
      "-Wconf:msg=package scala contains object and package with same name:s"
    ),
    libraryDependencies ++= Seq(
      // otoroshi brings wasm4s (bundle), scaffeine and pekko-connectors-s3 transitively
      "fr.maif" %% "otoroshi" % "18.0.0-preview2" % "provided",
      munit % Test
    )
  )
