name := "Core"

mainClass in Compile := Some("quasar.repl.Repl")

fork in run := true

connectInput in run := true

outputStrategy := Some(StdoutOutput)

import ScoverageSbtPlugin._

ScoverageKeys.coverageExcludedPackages := "quasar.repl;.*RenderTree"

ScoverageKeys.coverageMinimum := 78

ScoverageKeys.coverageFailOnMinimum := true

ScoverageKeys.coverageHighlighting := true

sbtbuildinfo.BuildInfoPlugin.projectSettings

buildInfoKeys := Seq[BuildInfoKey](version)

buildInfoPackage := "quasar.build"

wartremoverErrors in (Compile, compile) ++= Warts.allBut(
  // NB: violation counts are from running `compile`
  Wart.Any,                   // 113
  Wart.AsInstanceOf,          //  75
  Wart.ExplicitImplicitTypes, //   7 – see mpilquist/simulacrum#35
  Wart.IsInstanceOf,          //  79
  Wart.Nothing,               // 366
  Wart.Product,               // 180  _ these two are highly correlated
  Wart.Serializable,          // 182  /
  Wart.Throw,                 // 412
  Wart.ToString)              //  33
