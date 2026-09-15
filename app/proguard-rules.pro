# ProGuard / R8 configuration for FAIRY LIVE

# Keep ViewBinding generated classes
-keep class com.fairylive.app.databinding.** { *; }

# Keep data models used in state flows
-keep class com.fairylive.app.core.audio.model.** { *; }
-keep class com.fairylive.app.core.overlay.model.** { *; }
-keep class com.fairylive.app.core.settings.model.** { *; }

# Keep service and receiver components
-keep class com.fairylive.app.service.** { *; }
