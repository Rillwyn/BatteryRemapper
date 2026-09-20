# The modern Xposed API lives in the framework, so nothing here may be renamed or
# stripped when minification is enabled.
-keep class io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.service.** { *; }

# Entry point referenced by META-INF/xposed/java_init.list.
-keep class com.github.dhangofa.batteryremapper.BatteryHook { *; }

-dontwarn io.github.libxposed.annotation.**
