package im.tox.sbt

import im.tox.sbt.ConfigurePlugin.Configurations._
import im.tox.sbt.ConfigurePlugin.Keys._
import im.tox.sbt.ConfigurePlugin.{ Language, Cxx, C, NativeCompiler }
import im.tox.sbt.NativeCompilePlugin.Keys._
import im.tox.sbt.PkgConfigPlugin.autoImport._
import sbt.Keys._
import sbt._

object AndroidNdkPlugin extends AutoPlugin {

  override def trigger: PluginTrigger = noTrigger
  // Load after PkgConfigPlugin so we can remove -lpthread and -L flags.
  override def requires: Plugins = NativeCompilePlugin && PkgConfigPlugin

  object Keys {
    val ndkHome = settingKey[File]("Android NDK home.")
    val toolchainHome = settingKey[File]("Android toolchain directory.")
  }

  import Keys._

  private def nativeCompiler[L <: Language](language: L, program: String, toolchainHome: File, crossPlatform: String): NativeCompiler[L] = {
    NativeCompiler(
      language,
      (toolchainHome / "bin" / s"$crossPlatform-$program").getPath,
      "--sysroot=" + (toolchainHome / "sysroot").getPath
    )
  }

  private def removeBreakingLibs(flags: Seq[String]): Seq[String] = {
    flags.filterNot(flag => flag == "-lpthread" || flag.startsWith("-L"))
  }

  val androidSettings = Seq(
    // Hack to make "publishLocal" build the native library. Since tests don't run when building for Android,
    // there is otherwise no reason to build the library.
    publishLocal := publishLocal.dependsOn(nativeLink in NativeCompile).value,

    ndkHome := sys.env.get("ANDROID_NDK_HOME").map(file).filter(_.exists).getOrElse(file(sys.env("HOME")) / "android-ndk"),
    toolchainHome := baseDirectory.value / "toolchains" / crossPlatform.value,

    cc := nativeCompiler(C, "clang", toolchainHome.value, crossPlatform.value),
    cxx := nativeCompiler(Cxx, "clang++", toolchainHome.value, crossPlatform.value),

    // Ignore all flags from the environment variables.
    commonEnvFlags := Nil,
    cEnvFlags := Nil,
    cxxEnvFlags := Nil,
    ldEnvFlags := Nil,

    sources in NativeCompile += ndkHome.value / "sources/android/cpufeatures/cpu-features.c",

    pkgConfigPath := (toolchainHome.value / "sysroot" / "usr" / "lib" / "pkgconfig").getPath,
    jniIncludeFlags := Nil,

    ldConfigFlags += "-Wl,-z,defs",
    ldConfigFlags += "-latomic",

    ldConfigFlags := removeBreakingLibs(ldConfigFlags.value),
    libLdConfigFlags := removeBreakingLibs(libLdConfigFlags.value)
  )

  override def projectSettings: Seq[Setting[_]] = androidSettings

}
