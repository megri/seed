package seed.cli

import java.nio.file.Path

import seed.Log
import seed.artefact.{ArtefactResolution, SemanticVersioning}
import seed.cli.util.{Ansi, ColourScheme}
import seed.config.BuildConfig
import seed.model.{Artefact, Platform}
import seed.model.Platform.{JavaScript, Native}

object Update {
  def compareVersion(
    description: fansi.Str,
    oldVersion: String,
    version: Option[String],
    log: Log
  ): Unit =
    version match {
      case None =>
        println(
          ColourScheme.red1.toFansi(
            fansi.Str("⇎ ") ++ description ++
              " does not exist anymore"
          )
        )

      case Some(newVersion) =>
        val change = new SemanticVersioning(log).stringVersionOrdering
          .compare(oldVersion, newVersion)
        val versionChange = fansi.Str("(") ++ fansi.Bold.On(oldVersion) ++
          " → " ++ fansi.Bold.On(newVersion) ++ ")"

        change match {
          case -1 =>
            println(
              ColourScheme.yellow2.toFansi(
                fansi.Str("⬀ ") ++ description ++ " has a new version " ++ versionChange
              )
            )
          case 0 if oldVersion != newVersion =>
            println(
              ColourScheme.yellow2.toFansi(
                fansi
                  .Str("⬄ ") ++ description ++ " is up-to-date, but there is a different non-semantic version " ++ versionChange
              )
            )
          case 0 =>
            println(
              ColourScheme.green1.toFansi(
                fansi
                  .Str("⬄ ") ++ description ++ " is up-to-date (" ++ fansi.Bold
                  .On(oldVersion) ++ ")"
              )
            )
          case 1 =>
            println(
              ColourScheme.red1.toFansi(
                fansi
                  .Str("⬃ ") ++ description ++ " may be incompatible. Consider a downgrade " ++ versionChange
              )
            )
        }
    }

  def ui(path: Path, stable: Boolean, log: Log): Unit = {
    val result = BuildConfig.load(path, log).getOrElse(sys.exit(1))
    import result.build

    // As a heuristic, sort by number of dependencies to place root modules at
    // the beginning.
    val modules = build.values.map(_.module).toList.sortBy(_.moduleDeps.length)

    val buildArtefacts    = ArtefactResolution.allLibraryArtefacts(build)
    val scalaOrganisation = modules.flatMap(_.scalaOrganisation).head

    val (compilerVersions, platformVersions, libraryArtefacts) =
      new Scaffold(log).checkVersions(
        scalaOrganisation,
        BuildConfig.buildTargets(build),
        buildArtefacts.mapValues(_.map(Artefact.fromDep)),
        stable
      )

    println(Ansi.underlined("Compiler report"))

    BuildConfig.buildTargets(build).toList.sorted(Platform.Ordering).foreach {
      platform =>
        val oldCompilerVersion =
          modules
            .flatMap(m => BuildConfig.platformModule(m, platform))
            .flatMap(_.scalaVersion)
            .head
        val newCompilerVersion = compilerVersions.get(platform)

        compareVersion(
          fansi.Bold.On(platform.caption + ":") ++ " Scala compiler",
          oldCompilerVersion,
          newCompilerVersion,
          log
        )

        if (platform == JavaScript) {
          val oldPlatformVersion =
            modules.flatMap(_.js).flatMap(_.scalaJsVersion).head
          val newPlatformVersion = platformVersions.get(platform)

          compareVersion(
            fansi.Bold.On(platform.caption + ":") ++ " Scala.js plug-in",
            oldPlatformVersion,
            newPlatformVersion,
            log
          )
        } else if (platform == Native) {
          val oldPlatformVersion =
            modules.flatMap(_.native).flatMap(_.scalaNativeVersion).head
          val newPlatformVersion = platformVersions.get(platform)

          compareVersion(
            fansi.Bold.On(platform.caption + ":") ++ " Scala Native plug-in",
            oldPlatformVersion,
            newPlatformVersion,
            log
          )
        }
    }

    println()
    println(Ansi.underlined("Library report"))

    buildArtefacts.toList
      .sortBy(_._1)(Platform.Ordering)
      .zipWithIndex
      .foreach {
        case ((platform, deps), i) =>
          val latestArtefacts = libraryArtefacts.getOrElse(platform, Map())

          println(ColourScheme.blue1.toFansi(fansi.Bold.On(platform.caption)))

          deps.foreach { dep =>
            // TODO fansi does not support italics
            val description =
              fansi.Str("Dependency ") ++
                fansi.Underlined.On(dep.organisation) + ":" +
                fansi.Underlined.On(dep.artefact)
            val newVersion = latestArtefacts.get(Artefact.fromDep(dep)).flatten

            compareVersion(description, dep.version, newVersion, log)
          }

          if (i != buildArtefacts.size - 1) println()
      }
  }
}
