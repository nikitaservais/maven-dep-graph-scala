val scala3Version = "3.3.1"

lazy val root = project
  .in(file("."))
  .settings(
    name := "maven-dep-graph-scala",

    scalaVersion := scala3Version,
  )

libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.6.18"