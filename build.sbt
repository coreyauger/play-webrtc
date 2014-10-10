import com.typesafe.sbt.web.SbtWeb.autoImport._
import com.typesafe.sbt.less.Import.LessKeys
import NativePackagerKeys._

import com.typesafe.sbt.less.Import.LessKeys

import sbt.Keys._

import WebKeys._

packageDescription in Debian := "play-webrtc"

maintainer in Debian := "Corey Auger corey@nxtwv.com"

name := """play-webrtc"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.1"

libraryDependencies ++= Seq(
  jdbc,
  anorm,
  cache,
  ws
)

includeFilter in (Assets, LessKeys.less) := "*.less"

// for minified *.min.css files
LessKeys.compress := true
