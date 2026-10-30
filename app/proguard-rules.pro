# JSONPath / Jackson 保留规则（json-path 通过反射选择 provider）
-keep class com.jayway.jsonpath.** { *; }
-keep class net.minidev.json.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod

# json-path 的可选 provider（工程未引入，仅需忽略缺失类）
-dontwarn com.jayway.jsonpath.**
-dontwarn net.minidev.**
-dontwarn com.fasterxml.jackson.**
-dontwarn org.slf4j.**
-dontwarn jakarta.json.**
-dontwarn org.apache.tapestry5.**
-dontwarn org.codehaus.jettison.**
