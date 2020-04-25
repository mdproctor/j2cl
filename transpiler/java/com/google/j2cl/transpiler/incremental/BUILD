package(
    licenses = ["notice"],  # Apache 2.0
)

java_library(
    name = "incremental",
    srcs = glob(["*.java"]),
    visibility = ["//transpiler/java/com/google/j2cl/transpiler:__subpackages__",
                  "//transpiler/javatests/com/google/j2cl/transpiler:__subpackages__"],
    deps = [
        "//third_party:auto_value",
        "//third_party:guava",
        "//transpiler/java/com/google/j2cl/ast",
        "//transpiler/java/com/google/j2cl/ast/visitors",
        "//transpiler/java/com/google/j2cl/common",
        "//transpiler/java/com/google/j2cl/frontend",
        "//transpiler/java/com/google/j2cl/generator",
        "//transpiler/java/com/google/j2cl/transpiler:transpiler_options_lib",
    ],
)
