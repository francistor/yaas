name := "AAAServer"

version := "0.1"

organization := "com.gmail.franciscocardosogil"

enablePlugins(JavaAppPackaging)

libraryDependencies ++= 
  Seq(
    "com.typesafe.akka" %% "akka-actor" 	 % "2.5.14",
    "com.typesafe.akka" %% "akka-stream"     % "2.5.14",
    "com.typesafe.akka" %% "akka-slf4j"      % "2.5.14",
    "com.typesafe.akka" %% "akka-testkit"    % "2.5.14",
    "org.scalatest"     %% "scalatest"       % "3.0.5",
    "ch.qos.logback" 	%  "logback-classic" % "1.2.3",
    "org.json4s" 		%% "json4s-jackson"  % "3.6.0"
  )
  
scriptClasspath += "../conf"
batScriptExtraDefines += """set APP_CLASSPATH = %APP_LIB_DIR%\..\conf\%1;%APP_CLASSPATH%"""
batScriptExtraDefines += """call :add_java "-Dinstance=%1""""

bashScriptExtraDefines += """set app_classpath = $lib_dir/../conf/$1:$app_classpath"""
bashScriptExtraDefines += """call :add_java "-Dinstance=$1""""

  


