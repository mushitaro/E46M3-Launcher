# R8 is disabled in Phase 1 (see app/build.gradle.kts) and turned on in Phase 2.
# Rules are collected here as each dependency lands.

# NanoHTTPD (Phase 4, loopback server) reflects over its own method handlers.
# -keep class org.nanohttpd.** { *; }

# Keep the HOME activity name stable: it is referenced by
#   adb shell cmd package set-home-activity <pkg>/.HomeActivity
# and a renamed class would break the documented rollback procedure.
-keep class app.tsunagi.e46m3.launcher.HomeActivity { *; }
