// sbt/sbt-assembly#496
addSbtPlugin("com.eed3si9n"      % "sbt-assembly"       % "1.2.0")
addSbtPlugin("ch.epfl.scala"     % "sbt-bloop"          % "1.5.6")
addSbtPlugin("com.github.sbt"    % "sbt-license-report" % "1.5.0")
addSbtPlugin("com.lightbend.sbt" % "sbt-java-formatter" % "0.8.0")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"       % "2.5.0")
addSbtPlugin("com.simplytyped"   % "sbt-antlr4"         % "0.8.3")

libraryDependencies += "io.circe"  %% "circe-yaml" % "0.14.2"
libraryDependencies += "commons-io" % "commons-io" % "2.12.0"
libraryDependencies += "nl.gn0s1s" %% "bump"       % "0.1.3"
