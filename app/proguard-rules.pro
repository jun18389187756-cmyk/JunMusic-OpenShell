# Jun Music ProGuard Rules
#
# 原则：不要整包 -keep，否则 R8 无法裁剪未用代码，安装体积暴涨。
# 只保留反射/运行时真正需要、R8 无法静态推断的部分，其余交给 R8 shrink。

# ---- Media3 / ExoPlayer ----
# Media3 自带 consumer proguard 规则，会保留必要部分；仅关闭无关警告。
-dontwarn androidx.media3.**

# ---- Kotlin 协程/元数据 ----
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlin.Metadata { *; }

# ---- App：只保留带 @Keep 的成员，其余允许裁剪/混淆 ----
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
}
-keep @androidx.annotation.Keep class * { *; }
# ---- 混淆字典：用无语义标识符替换类名/方法名/包名，提高逆向成本 ----
-obfuscationdictionary proguard/obfuscation_dictionary.txt
-classobfuscationdictionary proguard/obfuscation_dictionary.txt
-packageobfuscationdictionary proguard/obfuscation_dictionary.txt

# ---- 关掉 R8 优化（5.2feat 闪退根因，别再打开） ----
#
# 线上现象：装完打开 App 第一帧就闪退，日志是
#   java.lang.VerifyError: Verifier rejected class
#   androidx.compose.foundation.text.TextFieldKeyInputKt$textFieldKeyInput$2$1$1
#   ... Rejecting invocation, expected 1 argument registers, method signature has 2 or more
# 调用栈落在 AndroidComposeView.onAttachedToWindow —— 也就是首帧装配 ComposeView 时
# ART 拒验这个类，进程直接死，任何页面都进不去。
#
# 原因：R8 的 class merging 把 Compose 内部的一个 lambda 类和 App 自己的多个
# Composable lambda（MainScreen / PlayerViewModel.setReportRange 等）合并成了同一个类，
# 合并后的 invoke 调用寄存器数对不上，ART 校验器直接拒收。
#
# 下面这行以前是 ProGuard 的写法，看着像"已经关掉了 class merging"：
#   -optimizations !code/simplification/...,!class/merging/*
#   -optimizationpasses 5
# 但 **R8 不实现 ProGuard 的 -optimizations / -optimizationpasses**，这两行是空保险：
# 合并照做。所以这里改成 R8 真正认的开关：-dontoptimize，优化全关，合并不再发生。
# 裁剪（shrink）、混淆（obfuscation）、资源压缩都仍然生效，体积基本不变。
-dontoptimize

# ---- 日志：-assumenosideeffects 依赖优化，-dontoptimize 下不再生效（保留仅作说明） ----
# 本工程源码里没有 Log.d/Log.v 调用（0 处），受影响只是 androidx/media3 内部的调试日志，
# 不含用户数据，留在包里可以接受。若以后要恢复"release 删日志"，
# 别再打开 -dontoptimize，改成在代码里用 BuildConfig.DEBUG 判断。
# -assumenosideeffects class android.util.Log {
#     public static int d(...);
#     public static int v(...);
# }
