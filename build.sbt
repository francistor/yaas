name := "AAAServer"

version := "0.1"

organization := "com.gmail.franciscocardosogil"

enablePlugins(JavaAppPackaging)

resolvers += Resolver.bintrayRepo("hseeberger", "maven")
libraryDependencies ++= 
  Seq(
    "com.typesafe.akka" %% "akka-actor" 	 % "2.5.14",
    "com.typesafe.akka" %% "akka-stream"     % "2.5.14",
    "com.typesafe.akka" %% "akka-slf4j"      % "2.5.14",
    "com.typesafe.akka" %% "akka-http"		 % "10.1.5",
    "com.typesafe.akka" %% "akka-testkit"    % "2.5.14",
    "org.scalatest"     %% "scalatest"       % "3.0.5",
    "ch.qos.logback" 	%  "logback-classic" % "1.2.3",
    "org.json4s" 		%% "json4s-jackson"  % "3.6.0",
    "de.heikoseeberger" %% "akka-http-json4s" % "1.21.0",
    "org.apache.ignite" % "ignite-core" % "2.7.0",
    "org.apache.ignite" % "ignite-spring" % "2.7.0",
    "org.apache.ignite" % "ignite-indexing" % "2.7.0",
    "org.apache.ignite" % "ignite-scalar" % "2.7.0",
    "org.apache.ignite" % "ignite-slf4j" % "2.7.0",
    "com.typesafe.slick" %% "slick" % "3.3.0",
    "com.typesafe.slick" %% "slick-hikaricp" % "3.3.0"
    
  )
  
scriptClasspath += "../conf"
batScriptExtraDefines += """if [%1] == [] (set INSTANCE=default) else (set INSTANCE=%1)"""
batScriptExtraDefines += """set APP_CLASSPATH=%APP_LIB_DIR%\..\conf\%INSTANCE%;%APP_CLASSPATH%"""
batScriptExtraDefines += """call :add_java "-Dinstance=%INSTANCE%""""

bashScriptExtraDefines += """set INSTANCE=${1:=default}"""
bashScriptExtraDefines += """set app_classpath = $lib_dir/../conf/$INSTANCE:$app_classpath"""
bashScriptExtraDefines += """call :add_java "-Dinstance=$INSTANCE""""

  


