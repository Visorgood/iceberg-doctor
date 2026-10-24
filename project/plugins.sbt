addSbtPlugin("com.github.sbt"   % "sbt-native-packager" % "1.11.7")
// `sbt dependencyUpdates` audits library dependencies against the repositories. It does not
// cover plugin versions — the sbt 1 `reload plugins` trick does not work under sbt 2, see
// the gotcha in AGENTS.md — so the two lines in this file still need a manual check.
addSbtPlugin("com.timushev.sbt" % "sbt-updates"         % "0.7.1")
