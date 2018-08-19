name := "AAAServer"

version := "0.1"

organization := "com.gmail.franciscocardosogil"

enablePlugins(JavaAppPackaging)

libraryDependencies ++= 
  Seq(
    "com.typesafe.akka" %% "akka-actor" 	 % "2.5.8",
    "com.typesafe.akka" %% "akka-stream"     % "2.5.8",
    "com.typesafe.akka" %% "akka-slf4j"      % "2.5.8",
    "com.typesafe.akka" %% "akka-testkit"    % "2.5.8",
    "org.scalatest"     %% "scalatest"       % "3.0.0",
    "ch.qos.logback" 	%  "logback-classic" % "1.2.3",
    "org.json4s" 		%% "json4s-jackson"  % "3.5.3"
  )
  


