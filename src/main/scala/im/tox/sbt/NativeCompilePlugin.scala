package im.tox.sbt

import im.tox.sbt.ConfigurePlugin.Configurations._
import im.tox.sbt.ConfigurePlugin.Keys._
import im.tox.sbt.ConfigurePlugin.NativeCompilationSettings
import sbt.Keys._
import sbt._

object NativeCompilePlugin extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins = ConfigurePlugin

  object Keys {
    val cppSource = settingKey[File]("Default C/C++ source directory")
    val objectDirectory = settingKey[File]("Directory for compiled native objects.")
    val nativeLibraryOutput = settingKey[File]("Name of the resulting shared library.")
    val nativeProgramOutput = settingKey[File]("Name of the resulting program.")
    val hostPlatform = settingKey[String]("Host platform on which the compiler is running.")
    val crossPlatform = settingKey[String]("Target platform for the native library.")
    val crossCompiling = settingKey[Boolean]("Whether we are cross-compiling (host != cross).")

    val nativeCompile = taskKey[Seq[File]]("Compile all native code to objects.")
    val nativeLink = taskKey[Option[File]]("Link native objects into a shared library.")
  }

  import Keys._

  def mapLibraryName(platform: String, name: String): String = {
    if (platform.contains("darwin")) {
      s"lib$name.dylib"
    } else {
      s"lib$name.so"
    }
  }

  def osName: String = {
    sys.props("os.name") match {
      case "Linux" => "linux"
      case "Mac OS X" => "darwin"
      case osName if osName.startsWith("Windows") =>
        "windows"
    }
  }

  def archName: String = {
    sys.props("os.arch") match {
      case "amd64" | "x86_64" => "x86_64"
    }
  }

  val targetConfigPaths = Seq(
    crossTarget := target.value / crossPlatform.value,
    objectDirectory := crossTarget.value / "objects",
    nativeLibraryOutput := crossTarget.value / mapLibraryName(crossPlatform.value, name.value)
  )

  val sourceConfigPaths = Seq(
    includeFilter in unmanagedSources := NativeCompilation.sourceFileFilter | NativeCompilation.headerFileFilter,

    sourceDirectory := {
      val src = (sourceDirectory in ThisScope.copy(config = Global)).value
      val conf = configuration.value
      val configName = conf.extendsConfigs.headOption.fold(conf.name)(_.name)
      src / Defaults.nameForSrc(configName)
    },
    cppSource := sourceDirectory.value / "cpp",
    unmanagedSourceDirectories := Seq(cppSource.value),
    unmanagedSources := Defaults.collectFiles(unmanagedSourceDirectories, includeFilter in unmanagedSources, excludeFilter in unmanagedSources).value,
    watchSources in Defaults.ConfigGlobal ++= unmanagedSources.value,
    managedSourceDirectories := Seq(sourceManaged.value),
    managedSources := Defaults.generate(sourceGenerators).value,
    sourceGenerators := Nil,
    sourceDirectories := Classpaths.concatSettings(unmanagedSourceDirectories, managedSourceDirectories).value,
    sources := Classpaths.concat(unmanagedSources, managedSources).value
  )

  val jdkHome = file(sys.props("java.home")).getParentFile

  def getEnvFlags(envVar: String): Seq[String] = {
    sys.env.get(envVar).map(_.split(' ')).toSeq.flatten
  }

  val compilerConfig = Seq(
    commonConfigFlags ++= sourceDirectories.value.map("-I" + _.toString) ++ jniIncludeFlags.value,

    // Link with version script to avoid exporting unnecessary symbols.
    ldConfigFlags ++= Configure.tryCompile(streams.value.log, cxx.value, {
      val versionScript = (cppSource.value / s"lib${name.value}.ver").getPath
      Seq(s"-Wl,--version-script,$versionScript")
    })
  )

  val compilation = Seq(
    nativeCompile := Def.taskDyn {
      NativeCompilation.compileSources(
        streams.value.log, streams.value.cacheDirectory / "native",
        NativeCompilationSettings(cc.value, cFlags.value),
        NativeCompilationSettings(cxx.value, cxxFlags.value),
        cppSource.value +: managedSourceDirectories.value,
        objectDirectory.value,
        sources.value
      )
    }.value
  )

  val allExceptLinking =
    Defaults.paths ++
      sourceConfigPaths ++
      targetConfigPaths ++
      compilerConfig ++
      compilation

  val linking = Seq(
    nativeLink := {
      NativeCompilation.linkSharedLibrary(
        streams.value,
        cxx.value, ldConfigFlags.value ++ ldEnvFlags.value ++ libLdConfigFlags.value,
        nativeCompile.value,
        nativeLibraryOutput.value
      )
    }
  )

  val nativeSettings = allExceptLinking ++ linking

  override def projectSettings: Seq[Setting[_]] = inConfig(NativeCompile)(nativeSettings) ++ Seq(
    hostPlatform := s"$archName-$osName",
    crossPlatform := hostPlatform.value,
    crossCompiling := crossPlatform.value != hostPlatform.value
  )

}
