# mhhook -- R8 rules (only used with --obf)
#
# The framework instantiates the entry class by name from java_init.list, so the
# class itself and its default constructor must survive. Every other member is
# renamed, so a dumped dex carries no readable hook layout.

-keep class com.fj.mhhook.MhHook { public <init>(); }
-keepnames class com.fj.mhhook.MhHook

-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*

# target classes are resolved reflectively at runtime; silence missing-type warnings
-dontwarn io.github.libxposed.**
-dontwarn io.flutter.plugin.common.**
-dontwarn com.anythink.**

-repackageclasses ''
-allowaccessmodification
-overloadaggressively
-adaptclassstrings
-renamesourcefileattribute X
