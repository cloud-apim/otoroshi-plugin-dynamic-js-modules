import Dependencies._

ThisBuild / scalaVersion     := "2.12.13"
ThisBuild / version          := "1.0.0-dev"
ThisBuild / organization     := "com.cloud-apim"
ThisBuild / organizationName := "Cloud-APIM"

lazy val root = (project in file("."))
  .settings(
    name := "otoroshi-plugin-dynamic-js-modules",
    resolvers += "jitpack" at "https://jitpack.io",
    libraryDependencies ++= Seq(
      "fr.maif" %% "otoroshi" % "17.0.0" % "provided" excludeAll(ExclusionRule("fr.maif", "wasm4s")),
      "fr.maif" %% "wasm4s" % "3.7.0" classifier "bundle",
      munit % Test
    )
  )
