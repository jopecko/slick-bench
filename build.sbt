enablePlugins(JmhPlugin)

name := "slick-bench"

organization := "io.github.jopecko"

version := "1.0"

scalaVersion := "2.12.8"

resolvers += Resolver.sonatypeRepo("releases")

libraryDependencies ++= Seq(
  "com.typesafe.slick" %% "slick" % "3.2.0",
  "com.h2database" % "h2" % "1.4.194",
  "com.danielasfregola" %% "random-data-generator" % "2.0",
  "org.slf4j" % "slf4j-nop" % "1.7.25"
)

scalacOptions ++= Seq(
  "-encoding", "utf8",
  "-Xfuture",
  "-feature",
  "-language:postfixOps",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:experimental.macros",
  "-deprecation",
  "-unchecked",
  "-Xlint",
  "-Yno-adapted-args",
  "-Ywarn-value-discard",
  "-Ywarn-dead-code"
)
