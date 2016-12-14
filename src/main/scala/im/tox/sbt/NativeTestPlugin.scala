package im.tox.sbt

import im.tox.sbt.ConfigurePlugin.Configurations._
import im.tox.sbt.ConfigurePlugin.Keys._
import im.tox.sbt.NativeCompilePlugin.Keys._
import sbt.Keys._
import sbt._

object NativeTestPlugin extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins = NativeCompilePlugin

  val targetConfigPaths = Seq(
    nativeProgramOutput := crossTarget.value / (name.value + "_test")
  )

  val compilerConfig = Seq(
    commonConfigFlags ++= (sourceDirectories in NativeCompile).value.map("-I" + _),

    ldConfigFlags ++= Seq(
      "-Wl,-rpath," + crossTarget.value,
      "-L" + crossTarget.value,
      "-l" + name.value
    )
  )

  val linking = Seq(
    sources := {
      if (crossCompiling.value) {
        Nil
      } else {
        sources.value
      }
    },

    nativeLink := NativeCompilation.linkProgram(
      streams.value,
      cxx.value, ldConfigFlags.value ++ ldEnvFlags.value ++ libLdConfigFlags.value,
      nativeCompile.value,
      (nativeLink in NativeCompile).value,
      nativeProgramOutput.value
    )
  )

  val running = Seq(
    fork in Test := false,
    javaOptions in Test ++= Seq(
      "-Xmx1g",
      "-Xbatch",
      "-Xcheck:jni",
      "-Djava.library.path=" + crossTarget.value
    )
  )

  val nativeSettings =
    NativeCompilePlugin.allExceptLinking ++
      targetConfigPaths ++
      compilerConfig ++
      linking ++
      running

  override def projectSettings: Seq[Setting[_]] = inConfig(NativeTest)(nativeSettings)

}
