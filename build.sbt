
name := "evernote-analytics"

version := "1.0"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

mainClass in assembly := Some("play.core.server.NettyServer")

fullClasspath in assembly += Attributed.blank(PlayKeys.playPackageAssets.value)

libraryDependencies ++= Seq(
  ws,
  specs2,
  "com.evernote" % "evernote-api" % "1.25.1" withSources()
)

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"

